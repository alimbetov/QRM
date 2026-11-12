# QRM — React Web и Flutter UX / navigation specification

**Статус:** UX acceptance specification; макеты и E2E ещё не реализованы.

## 1. React публичная витрина

### Routes
- `/p/:publicId` — карточка объявления, public/unlisted only.
- `/s/:slug` — продавец: логотип, ник, рейтинг, его публичные объявления.
- `/q/:qrToken` — атрибуция QR и redirect на профиль/товар.
- `/seller/login` — Web QR login.
- `/seller/dashboard`, `/seller/inbox`, `/seller/listings`, `/seller/orders`, `/seller/analytics`, `/seller/sessions` — seller cabinet после авторизации.

### Product page
Логотип продавца, имя и линк на его страницу, фото 3–5 с галереей, название/описание RU/KK/EN/ZH с fallback, характеристика, тип PHYSICAL/SERVICE/DIGITAL, единица, quantity tier, текущая скидка/срок, YouTube, рейтинг, публичный счётчик просмотров. `heart` серый и неактивный с подсказкой про Flutter; не сохранять web favorite. На archived/404 корректная отдельная страница без submit.

Web callback form: телефон, количество, «согласен с ценой / хочу предложить цену», комментарий, согласие на обратный звонок. Submit → confirmation с request reference и информацией «продавец может связаться»; **без обещания заказа**. Доступность формы зависит от Listing state; есть антибот/rate-limit и защита PII. Валидация decimal и unit, явное сообщение об ошибке. Кнопка «Открыть в приложении» с App Links, fallback install links.

## 2. React Seller Cabinet

Основные экраны:
1. Dashboard: новые обращения, ожидающие цены, активные заказы, QR views, без финансовых обещаний.
2. Inbox: три категории LEAD / NEGOTIATION / ORDER; вкладки ALL/UNREAD/READ/BOOKMARKED, фильтры статус/период/listing, count unread, cursor pagination 30/page.
3. Lead detail: телефон покупателя только при авторизованном просмотре, цена/количество, отмечать «перезвонил», «неинтересно», контекст открытия, без кнопки auto-create order.
4. Negotiation: история раундов, current offer and expiry, accept/counter/reject, явная кнопка «Создать заказ» только после AGREED; показывает remaining round count.
5. Order: price snapshot, статус, события, fulfillment type, buyer cancellation requests.
6. Listing manager: черновик, загрузка фото, каталог и ед. измерения, локали, tiers, timed discounts, YouTube, QR download.
7. Seller profile: логотип, slug, публичная страница, ошибки модерации.
8. Analytics: просмотры по времени, QR source, funnel view→lead/order (с разделением реальной конверсии и условного связывания).
9. Sessions: перечислить текущие устройства, завершить конкретную/все сессии.

Inbox opening an item: mark read based on observed activityVersion only after detail load; new incoming activity restores unread. Bookmark toggles independent of read and business state. На web и Flutter одинаковый серверный результат.

## 3. Flutter Buyer

Экран входа/профиля; открытие по App Link точного объявления; избранное и price watch (`UNIT` либо `QUANTITY`), push preferences, карточка, предложение цены, обсуждение (5 раундов), согласие, заказ, получение, претензии, отзывы с фото. Buyer может withdraw незавершённое предложение, cancel неподтверждённый заказ, request cancellation для confirmed/in-progress.

Избранное хранится сервером, а не только локально; offline state queue только если отдельно согласовано. Push deep link открывает конкретное объявление после проверки актуальной цены/статуса.

## 4. Flutter Seller

Управление объявлениями, фото, ценами, скидками, QR, Seller Inbox с bookmark/read, обратные звонки по web leads, торг, заказы, аналитика, настройки уведомлений, просмотр активных Web-сессий.

### QR login journey
1. Открыть /seller/login → QR с challenge expiry.
2. Flutter сканирует QR in-app и показывает origin, device/session details.
3. Продавец явно подтверждает (либо отклоняет) вход.
4. React показывает approved и создаёт Web-сессию по одноразовой привязке.
5. QR expire/refresh/replay/deny имеют явные экраны.
6. Завершение Web-сессии с телефона инвалидирует доступ в Web.

Не разрешать QR approve в фоне или по простому открытию deeplink.

## 5. Accessibility, localization и UI states

- KZ/RU/EN/ZH локали интерфейса. Отсутствующий перевод объявления fallback; системные тексты локализуются самостоятельно.
- Mobile-first layout React, доступная клавиатурная навигация; цвет не единственный сигнал unread.
- Loading/empty/error/permission/expired/offline/retry states обязательны на списках и деталях.
- Цены KZT и дробные quantities отображаются с осмысленной точностью и понятными единицами.
- Часовые пояса продавца и UTC canonical timestamps; в UI явная локальная дата акции.
- Не показывать seller contact или покупательский номер в публичной карточке без явного решения.
- Никогда не считать «просмотрено», «связались», «согласовано», «оформлено» синонимами.

## 6. UX acceptance journeys

- QR printed → view product without auth, same URL after price change.
- React form validates, submits and confirms only callback request.
- Deep link opens precise listing in Flutter, favorite toggle and watch persisted.
- Seller filters inbox, saves bookmark, opens item, new incoming offer returns unread on both devices.
- QR login approved/rejected/expired and remote revoke behave correctly.
- Flutter seller accepts offer → visible AGREED → explicit one-time create order; buyer receives status.
- Refund/claim initiation depends on product type and stage, no silent financial reversal.

## DEC-27 — Password/Auth and Notifications UX

Flutter registration: phone login + password + nickname, optional email; display clear notice that phone number is not verified. Login with password, session reuse/secure refresh and logout. Optional verified-email recovery; without verified factor do not promise automated recovery. Notification Center: tabs All/Unread and unread counts, history durable when FCM off, deep-links to authorized business details; reading notifications does not change seller inbox/offer/order. Web seller QR approval must occur inside already signed-in Flutter, push alert cannot approve automatically. [Complete spec](AUTH-NOTIFICATIONS.md).

## Карта переходов и последовательностей MVP

Согласованная карта экранов и доменных действий, схемы для React Web, Flutter Buyer/Seller, QR-login, уведомлений, fulfillment и reputation: [USER-JOURNEYS-MVP.md](USER-JOURNEYS-MVP.md). Таблицы UX-01…16/EDGE-01…20 являются проектными acceptance-критериями. Обязательно отображать отдельные состояния AGREED vs Order PLACED и Web Lead vs электронный заказ.
