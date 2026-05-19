# Privacy Policy

**Last updated: 2026-05-19**

This Privacy Policy describes how the operator of the **Tyche** Telegram bot
(the "Bot") collects, uses, and protects personal data of its users. It is
written to comply with the **EU General Data Protection Regulation (Regulation
(EU) 2016/679, "GDPR")** and equivalent applicable laws.

> **Note for self-hosted instances.** The Bot's source code is open-source
> (MIT-licensed). Anyone may run their own instance. **Each operator of a
> self-hosted instance is an independent data controller** with respect to data
> processed by that instance. This Privacy Policy applies only to the instance
> operated by the maintainer identified in [Section 1](#1-data-controller). If
> you fork or self-host the Bot, you are responsible for producing your own
> privacy policy.

---

## 1. Data Controller

The data controller responsible for the processing of personal data described
in this policy is:

- **Artjoms Šutovs** (Latvia), maintainer of the Tyche Bot instance
- **Contact:** floating.morality@gmail.com
- **GitHub:** https://github.com/floating-morality

If you have any question about this policy or wish to exercise any of your
rights described below, please contact us at the address above.

---

## 2. Categories of Personal Data Collected

The Bot processes the following categories of personal data:

| Category                                    | Source                                                                    | Purpose                                                                     |
|---------------------------------------------|---------------------------------------------------------------------------|-----------------------------------------------------------------------------|
| **Telegram chat ID**                        | Telegram, in every update routed to the Bot from the chat                 | Identify the chat in which command state must be kept                       |
| **Telegram user ID**                        | Telegram, when a user issues a command or is added to a chat with the Bot | Identify the user who issued a command, or who added the Bot to a chat      |
| **Telegram username** (if the user has one) | Telegram, alongside the user ID                                           | Logged for operational debugging; not stored persistently                   |
| **Items**                                   | User-provided via `/set_items` or `/add_item`                             | Stored to support the Bot's primary function — picking a random item        |
| **Message template**                        | User-provided via `/set_template`                                         | Stored to customize the result message of `/random`                         |

The Bot does **not** collect: phone numbers, email addresses, payment details,
location data, biometric data, or behavioural profiles.

To detect replies to its own prompts (specifically the prompt issued by
`/set_items`), the Bot inspects the text and sender of reply messages in
chats where it is present. Content of replies that do not match a pending
Bot prompt is examined transiently and immediately discarded — it is not
stored and not written to operational logs. The Bot does not otherwise read
or retain message content beyond the commands listed in the table above.

**Note on items.** Items are free-form text supplied by users of the chat.
When users add the names of other people as items (via `/set_items` or
`/add_item`), they are responsible for ensuring they have a lawful basis to
share those names with the Bot (for example, the consent of the persons
concerned, or another basis under GDPR Art. 6). The Bot operator does not
verify the identity of, or the relationship between, the user adding an
item and any person it may refer to.

---

## 3. Legal Basis for Processing (GDPR Art. 6)

Processing is based on:

- **Article 6(1)(b) — performance of a contract:** the user adds the Bot to a
  chat and issues commands; processing is necessary to fulfil the requested
  service.
- **Article 6(1)(f) — legitimate interests:** short-term operational logging
  (chat ID, user ID and username at command invocation, together with
  command arguments such as item text and templates) is processed for the
  legitimate purpose of debugging and abuse prevention. Our assessment finds
  that this does not override the rights and interests of users given the
  minimal scope and short retention.

---

## 4. How Data Is Stored

- **Persistent state** (chat IDs, items, message templates) is stored in a
  JSON file (`items.json` by default) on infrastructure operated by the
  maintainer.
- **Transient state** (active `/random` sessions, pending `/set_items`
  prompts) is held only in memory and is automatically deleted after a
  time-to-live of **1 minute** (random) or **5 minutes** (set items).
- **Operational logs** are written by the Bot process and rotated by the
  application's logging configuration (Logback). They contain, at command
  invocation: chat ID, user ID and username, and the command's arguments
  where applicable — including item text added or removed, the contents of
  set/replaced item lists, message templates, and the winner of a random
  pick. Error traces are also logged.

No data is replicated to third-party storage services.

---

## 5. Retention Period

| Data                                 | Retention                                                                                                                                                                                                  |
|--------------------------------------|------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| Items, message templates             | Until the user explicitly overwrites or clears them via Bot commands, **or until the Bot is removed from the chat** (whichever comes first); on removal, the chat's stored data is automatically deleted   |
| In-memory sessions/prompts           | 1–5 minutes (TTL)                                                                                                                                                                                          |
| Operational logs                     | **No more than 30 days**, then automatically rotated/deleted                                                                                                                                               |

You may request earlier deletion at any time (see [Section 8](#8-your-rights)).

---

## 6. Recipients and Third Parties

Personal data is **not shared with any third party for marketing,
analytics, or profiling purposes**.

The Bot necessarily transmits messages and commands through **Telegram**,
which is the platform on which the Bot operates. Telegram's own privacy
practices apply to the underlying message transport; see
<https://telegram.org/privacy>.

The Bot's hosting infrastructure is provided by **Hetzner Online GmbH**
(Germany, EEA), acting as a data processor under Hetzner's standard Data
Processing Agreement pursuant to GDPR Art. 28. Hetzner does not access the
stored data in the ordinary course of providing the hosting service.

No other processors, analytics providers, or advertisers receive data
processed by the Bot.

---

## 7. International Transfers

The Bot's server and stored data are located within the European Economic
Area (EEA). Messages between users and the Bot are transported by Telegram,
whose infrastructure is global; Telegram is the controller of that
transport and provides its own safeguards.

---

## 8. Your Rights

Under the GDPR, you have the following rights with respect to your personal
data:

- **Right of access** (Art. 15): request a copy of personal data we hold
  about you.
- **Right to rectification** (Art. 16): request correction of inaccurate
  data.
- **Right to erasure / "right to be forgotten"** (Art. 17): request deletion
  of your data.
- **Right to restriction of processing** (Art. 18).
- **Right to data portability** (Art. 20).
- **Right to object** (Art. 21) to processing based on legitimate interests.
- **Right to lodge a complaint** with the data protection supervisory
  authority in your EU country of residence (Art. 77). The supervisory
  authority for the controller is the Latvian **Data State Inspectorate
  (Datu valsts inspekcija)** — https://www.dvi.gov.lv.

To exercise any of these rights, write to **floating.morality@gmail.com**. We
will respond within **30 days** of receipt.

You may also self-service:

- View current items: `/list_items`
- View current template: `/show_template`
- Remove individual items: `/remove_item`, then pick the item to delete
  from the inline keyboard.
- Replace the full item list: `/set_items`, then reply with the new items
  (one per line).
- Replace the message template: `/set_template <template>`.

If you wish to have the stored data for your chat deleted entirely, contact
us at the email above and we will remove it.

---

## 9. Automated Decision-Making

The Bot performs a random selection among items when the `/random` command
is issued. This selection is **not used to produce legal effects or
otherwise significantly affect any person** within the meaning of GDPR
Article 22. No profiling is performed.

---

## 10. Children

The Bot is not directed at children under 13 (or under 16 in jurisdictions
that apply that threshold under GDPR Art. 8). The Bot does not knowingly
collect data from children. If you become aware that a child has interacted
with the Bot, please contact us at **floating.morality@gmail.com** and we will
delete the associated data.

---

## 11. Security

Personal data is held on infrastructure operated by the maintainer. We rely
on commercially reasonable technical and organisational measures provided by
the hosting environment, including operating-system level isolation and TLS
for all communication with the Telegram API. No system can be guaranteed
absolutely secure; if a personal data breach occurs that is likely to result
in a risk to the rights and freedoms of natural persons, we will notify the
competent supervisory authority within **72 hours** as required by GDPR
Article 33, and affected users where required by Article 34.

---

## 12. Changes to This Policy

We may update this Privacy Policy from time to time. Material changes will
be announced in the Bot's release notes on GitHub. The "Last updated" date
at the top of this document reflects the latest revision.

---

## 13. Contact

For any questions, requests, or complaints regarding this Privacy Policy:

- **Email:** floating.morality@gmail.com
- **GitHub Issues:** https://github.com/floating-morality/tyche/issues
