package tyche.util

import cats.effect.syntax.resource.*
import cats.effect.syntax.spawn.*
import cats.effect.{Async, Ref, Resource, Temporal}
import cats.syntax.flatMap.*
import cats.syntax.functor.*

import scala.concurrent.duration.*

/** Concurrent `Map[K, V]` with per-entry time-to-live.
  *
  * Each entry is automatically forgotten after `entryTtl` has elapsed since its `put`. Stale entries are removed in two
  * ways:
  *   1. **Lazy** — on `get`, an entry past its TTL is treated as absent and dropped from the underlying state in the
  *      same atomic step.
  *   2. **Background** — a fiber spawned at construction sweeps the whole map every `sweepInterval`, dropping entries
  *      past their TTL. The fiber is tied to the enclosing [[cats.effect.Resource]] and cancelled on its release.
  *
  * TTL is anchored to **write time** (`put`). `modify` preserves the original timestamp; calling `get` does not extend
  * the lifetime.
  *
  * Time is read from [[cats.effect.kernel.Clock#monotonic]], which is immune to wall-clock adjustments (NTP, manual
  * clock changes) — what we actually want for "did N seconds pass".
  *
  * @param entryTtl
  *   how long each entry stays fresh from the moment of its `put`
  */
class TtlMap[F[_]: Temporal, K, V] private (
    ref: Ref[F, Map[K, (V, FiniteDuration)]],
    entryTtl: FiniteDuration
):

  /** Returns the value if present and still fresh; lazily evicts a stale entry in the same step. */
  def get(key: K): F[Option[V]] =
    Temporal[F].monotonic >>= { now =>
      ref.modify { map =>
        map.get(key).filter { case (_, ts) => isActive(ts, now) } match
          case Some((v, _)) => (map, Some(v))
          case None         => (map - key, None)
      }
    }

  /** Inserts with a fresh TTL window. Overwrites any existing entry. */
  def put(key: K, value: V): F[Unit] =
    Temporal[F].monotonic >>= { now =>
      ref.update(_.updated(key, (value, now)))
    }

  /** Updates the value preserving its original timestamp (TTL window does not reset). No-op if the key is absent.
    */
  def modify(key: K, f: V => V): F[Unit] =
    ref.update { map =>
      map.get(key) match
        case Some((v, ts)) => map.updated(key, (f(v), ts))
        case None          => map
    }

  /** Removes the entry if present. */
  def remove(key: K): F[Unit] = ref.update(_ - key)

  private def isActive(ts: FiniteDuration, now: FiniteDuration): Boolean =
    (now - ts) < entryTtl

  private def sweep: F[Unit] =
    Temporal[F].monotonic >>= { now =>
      ref.update(_.filter { case (_, (_, ts)) => isActive(ts, now) })
    }

  private[util] def sweepLoop(interval: FiniteDuration): F[Unit] =
    val tick =
      for
        _ <- Temporal[F].sleep(interval)
        _ <- sweep
      yield ()
    tick.foreverM

object TtlMap:

  val DefaultSweepInterval = 1.minute

  /** Creates a [[TtlMap]] and supervises its background sweep fiber under the returned [[cats.effect.Resource]].
    *
    * @param entryTtl
    *   how long each entry stays fresh after `put`
    * @param sweepInterval
    *   how often the background fiber scans the map and drops stale entries; lazy eviction in `get` is the primary
    *   defence, the background sweep just prevents unbounded growth in keys that are never read again
    */
  def make[F[_]: Async, K, V](
      entryTtl: FiniteDuration,
      sweepInterval: FiniteDuration = DefaultSweepInterval
  ): Resource[F, TtlMap[F, K, V]] =
    for
      ref <- Ref.of[F, Map[K, (V, FiniteDuration)]](Map.empty).toResource
      m = new TtlMap[F, K, V](ref, entryTtl)
      _ <- m.sweepLoop(sweepInterval).background
    yield m
