package tyche.util

import cats.Applicative
import cats.effect.syntax.resource.*
import cats.effect.syntax.spawn.*
import cats.effect.{Async, Ref, Resource, Temporal}
import cats.syntax.applicative.*
import cats.syntax.applicativeError.*
import cats.syntax.flatMap.*
import cats.syntax.foldable.*
import cats.syntax.functor.*
import org.typelevel.log4cats.syntax.*
import org.typelevel.log4cats.{Logger, LoggerFactory}

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
  * Each eviction (lazy or background) invokes the `onEvict` callback. Errors from the callback are caught and logged,
  * so a misbehaving callback never crashes the sweep fiber or surfaces in `get`.
  *
  * TTL is anchored to **write time** (`put`). `modify` preserves the original timestamp; calling `get` does not extend
  * the lifetime.
  *
  * Time is read from [[cats.effect.kernel.Clock#monotonic]], which is immune to wall-clock adjustments (NTP, manual
  * clock changes) — what we actually want for "did N seconds pass".
  *
  * @param entryTtl
  *   how long each entry stays fresh from the moment of its `put`
  * @param onEvict
  *   side effect run for each entry that gets removed because its TTL has elapsed. Not invoked on explicit `remove`.
  */
class TtlMap[F[_]: {Temporal, LoggerFactory}, K, V] private (
    ref: Ref[F, Map[K, (V, FiniteDuration)]],
    entryTtl: FiniteDuration,
    onEvict: (K, V) => F[Unit]
):

  private given Logger[F] = LoggerFactory[F].getLogger

  /** Returns the value if present and still fresh; lazily evicts a stale entry in the same step. */
  def get(key: K): F[Option[V]] =
    Temporal[F].monotonic >>= { now =>
      ref.modify { map =>
        map.get(key) match
          case Some((v, ts)) if isActive(ts, now) => (map, (Some(v), None))
          case Some((v, _))                       => (map - key, (Option.empty[V], Some(key -> v)))
          case None                               => (map, (Option.empty[V], None))
      } >>= { case (result, evicted) =>
        evicted
          .traverse_ { case (k, v) =>
            for
              _ <- info"TtlMap lazy-evicted stale entry: key=$k"
              _ <- onEvictSafe(k, v)
            yield ()
          }
          .as(result)
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

  /** Removes the entry if present. Does not invoke `onEvict` — only TTL-driven eviction does. */
  def remove(key: K): F[Unit] = ref.update(_ - key)

  private def isActive(ts: FiniteDuration, now: FiniteDuration): Boolean =
    (now - ts) < entryTtl

  private def onEvictSafe(k: K, v: V): F[Unit] =
    onEvict(k, v).handleErrorWith { e =>
      warn"TtlMap onEvict failed for key=$k: ${e.getMessage}"
    }

  private def sweep: F[Unit] =
    Temporal[F].monotonic >>= { now =>
      ref.modify { map =>
        val (active, expired) = map.partition { case (_, (_, ts)) => isActive(ts, now) }
        (active, expired)
      } >>= { expired =>
        if expired.isEmpty then ().pure[F]
        else
          val keys = expired.keys.mkString(",")
          for
            _ <- info"TtlMap sweep is going to evict ${expired.size} entries: keys=[$keys]"
            _ <- expired.toList.traverse_ { case (k, (v, _)) => onEvictSafe(k, v) }
          yield ()
      }
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
    * @param onEvict
    *   side effect run for each entry evicted by TTL (lazy or background sweep). Default is a no-op. Useful for
    *   reclaiming resources tied to the entry — e.g. deleting an orphaned Telegram prompt message.
    */
  def make[F[_]: {Async, LoggerFactory}, K, V](
      entryTtl: FiniteDuration,
      sweepInterval: FiniteDuration = DefaultSweepInterval
  ): Resource[F, TtlMap[F, K, V]] =
    makeWithOnEvict(entryTtl, sweepInterval, (_, _) => Applicative[F].unit)

  /** Variant of [[make]] that wires up a callback for TTL-driven evictions. */
  def makeWithOnEvict[F[_]: {Async, LoggerFactory}, K, V](
      entryTtl: FiniteDuration,
      sweepInterval: FiniteDuration,
      onEvict: (K, V) => F[Unit]
  ): Resource[F, TtlMap[F, K, V]] =
    for
      ref <- Ref.of[F, Map[K, (V, FiniteDuration)]](Map.empty).toResource
      m = new TtlMap[F, K, V](ref, entryTtl, onEvict)
      _ <- m.sweepLoop(sweepInterval).background
    yield m
