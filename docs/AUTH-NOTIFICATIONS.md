# DEC-27 — вход без SMS и внутренние уведомления

**Статус:** APPROVED — бизнес-решение. Технические настройки требуют R0/S2 review.

## 1. Identity и вход

- Первая регистрация Flutter: телефон в E.164 как логин + никнейм + пароль; email опционален. Аккаунт может быть BUYER и SELLER.
- **Телефон без SMS остаётся UNVERIFIED.** Нельзя утверждать, что пользователь владеет введённым номером, и использовать его как средство восстановления без проверки.
- Хранение пароля: Argon2id и при необходимости server-side pepper, настройки хеша с версией. Rate-limit и защита от перебора/перечисления аккаунтов; запрет секретов в логах.
- Вход: пароль и идентификатор; Flutter хранит access token короткое время и rotating refresh token в защищённом хранилище ОС. Повторное применение refresh token аннулирует семейство токенов.
- Вход в React Seller Cabinet подтверждается только из **уже авторизованного Flutter** через одноразовый QR challenge, связан с исходным браузером и явным подтверждением продавца. Push лишь уведомляет, не является доказательством контроля аккаунта.
- Email при указании первоначально UNVERIFIED. Для email recovery необходим механизм проверки ссылки через подключённый email-провайдер. Если проверенного email и доверенного авторизованного устройства нет, автоматическое восстановление недоступно; безопасная ручная процедура ещё должна быть согласована.
- Анонимный Web callback lead без OTP, но с антиспамом и одноразовой секретной ссылкой отзыва, привязанной к конкретной заявке. Номер из lead нельзя автоматически связывать с аккаунтом.

## 2. Internal Notification Center — источник истины

Business transaction → Transactional Outbox → Notification projector → PostgreSQL user_notification → optional Push delivery adapter (FCM).

- Внутреннее уведомление сохраняется независимо от Push. Отказ FCM не блокирует заказ, согласование и Web-заявку.
- Новая заявка, новое предложение, решение по предложению, новый заказ, изменение статуса, запрос отмены и снижение отслеживаемой цены создают уведомления по политике.
- Дедупликация по recipient + eventId + kind. Повторная доставка Push возможна, но дублирующиеся записи центра уведомлений недопустимы.
- Статусы read/unread отдельны от Seller Inbox bookmark/read и не меняют состояние Offer, Lead или Order.
- Push не должен включать телефон/адрес/токены. Цена и маркетинговые push только с opt-in. Уведомления о безопасности не отключать одной маркетинговой настройкой.
- Устройства с отозванной сессией и недействительными FCM-токенами исключаются из отправки.

## 3. API, проектные маршруты

POST /api/v1/auth/register
POST /api/v1/auth/login
POST /api/v1/auth/refresh
POST /api/v1/auth/logout
POST /api/v1/auth/password/change
POST /api/v1/auth/email/verification/request
POST /api/v1/auth/email/verification/confirm
POST /api/v1/auth/recovery/request
POST /api/v1/auth/recovery/complete
GET /api/v1/me/notifications?read=false&limit=30&cursor=...
GET /api/v1/me/notifications/unread-count
PUT /api/v1/me/notifications/{id}/read-state
PUT /api/v1/me/notification-preferences
POST /api/v1/me/push-devices
DELETE /api/v1/me/push-devices/{id}

## 4. Минимальные таблицы

- user_credential(user_id PK, password_hash, password_scheme, password_changed_at, version)
- user_email(user_id, normalized_email, verified_at nullable)
- auth_refresh_session(id, user_id, device_id, token_hash, family_id, expires_at, revoked_at)
- user_notification(id, recipient_user_id, event_id, kind, payload_json, created_at, read_at)
- user_notification_preference(user_id, category, push_enabled)
- push_device(id, user_id, platform, encrypted_token, revoked_at)
- notification_delivery(id, notification_id, channel, state, attempts)

Unique constraint recipient_user_id + event_id + kind. Encrypt sensitive tokens at rest. Source of truth: internal notification row.

## 5. RED тесты

| ID | Сценарий | Оракул |
|---|---|---|
| AUTH-01 | Регистрация без SMS | аккаунт создан, phoneVerified=false |
| AUTH-02 | Два одинаковых нормализованных логина | уникальность/нет утечки |
| AUTH-03 | Неверный пароль и brute force | единая ошибка, rate-limit |
| AUTH-04 | Refresh replay | семья токенов отозвана |
| AUTH-05 | Logout и device revoke | токен/сессия больше не действуют |
| AUTH-06 | Recovery без проверенного фактора | запрещено |
| AUTH-07 | Проверенный email recovery | одноразовая ограниченная ссылка |
| AUTH-08 | QR approval только из авторизованного Flutter | один браузер, один consume |
| AUTH-09 | Только FCM device token | не авторизует запрос |
| AUTH-10 | Непроверенный телефон | не отображается как проверенный |
| NOT-01 | FCM недоступен при создании заказа | заказ и внутреннее уведомление сохранены |
| NOT-02 | Outbox replay x3 | одна запись уведомления |
| NOT-03 | Прочтение на Flutter, затем Web | синхронизация read-state |
| NOT-04 | Чужая notification id | 403/404 |
| NOT-05 | Отказ от маркетингового Push | push не отправлен, in-app доступен |
| NOT-06 | Отзыв push устройства | доставка остановлена |
| NOT-07 | Чтение уведомления | не изменяет Offer/Order |
| NOT-08 | Новое событие + конкурентная отметка read | точный счётчик |
| NOT-09 | Анонимная Web-заявка | нет OTP, защищён отзыв |
| NOT-10 | Отметки Seller Inbox и Notification Center | независимые состояния |

## 6. Блокеры до production

Определить политику паролей и сессий, способ реальной email-верификации и recovery, последствия непроверенного номера, согласия на push, retention, phishing-защиту QR-входа. OpenAPI и миграции после S2. Сейчас только ТЗ, не реализация.