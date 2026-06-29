# ⚡ Світло UA — Android App v4.0

## Що потрібно для збірки

1. **Android Studio** (безкоштовно): https://developer.android.com/studio
   - Завантажити → встановити → відкрити цю папку як проект

2. **Більше нічого** — Android Studio автоматично завантажить Gradle та SDK

---

## Як зібрати APK (покрокова інструкція)

### Крок 1 — Відкрити проект
```
Android Studio → File → Open → вибрати папку SvitloUA
```
Дочекайся Gradle sync (~1-2 хв, потрібен інтернет перший раз)

### Крок 2 — Зібрати Debug APK
```
Build → Build Bundle(s) / APK(s) → Build APK(s)
```
APK буде у: `app/build/outputs/apk/debug/app-debug.apk`

### Крок 3 — Встановити на телефон
**Варіант A — через кабель:**
```
Run → Run 'app'  (телефон має бути підключений з увімкненим USB debugging)
```

**Варіант B — скопіювати APK вручну:**
- Скопіюй `app-debug.apk` на телефон
- На телефоні: Налаштування → Безпека → Встановлення з невідомих джерел → ввімкнути
- Відкрий файл APK через файловий менеджер → встановити

---

## Структура проекту

```
SvitloUA/
├── app/src/main/
│   ├── assets/index.html      ← ВСЯ ЛОГІКА ДОДАТКУ ТУТ
│   ├── java/ua/svitlo/app/
│   │   └── MainActivity.java  ← WebView + Notification bridge
│   ├── res/
│   │   ├── layout/            ← UI layout
│   │   ├── mipmap-*/          ← Іконки всіх розмірів
│   │   └── values/            ← Теми, рядки
│   └── AndroidManifest.xml
├── build.gradle
└── settings.gradle
```

---

## Що змінено у v4.0 vs оригіналу

| Проблема | Рішення |
|---------|---------|
| Київ показував 60+ черг | Групування: N.1..N.6 → Гр.1..Гр.6 (макс 6 груп) |
| Немає захисту від DDoS | Rate limiter (3 req/min) + exponential backoff |
| API падає → білий екран | Кеш 5 хв + fallback на кеш при помилці |
| Сповіщення не працювали | Android bridge: window.AndroidBridge.showNotification() |
| Немає перевірки при запуску | Splash screen з 3 перевірками (мережа/API/дані) |
| Помилка без кнопки retry | Кнопка "Спробувати знову" у error box |

---

## Чи потрібен свій сервер?

**НІ** — додаток використовує вже існуючий проксі:
```
https://dtek-api.svitlo-proxy.workers.dev/
```
Це Cloudflare Worker, який ти не контролюєш, але він безкоштовний і публічний.

**Якщо хочеш свій проксі** (для стабільності):
1. Зареєструйся на cloudflare.com (безкоштовно)
2. Workers → Create Worker → вставити код нижче → задеплоїти

```javascript
export default {
  async fetch(request) {
    const DTEK = 'https://api-prod.dtek.ua/...'; // реальний DTEK API
    const res = await fetch(DTEK, {
      headers: {'User-Agent':'Mozilla/5.0','Accept':'application/json'}
    });
    const data = await res.json();
    return new Response(JSON.stringify(data), {
      headers: {
        'Content-Type': 'application/json',
        'Access-Control-Allow-Origin': '*',
        'Cache-Control': 'public, max-age=300'
      }
    });
  }
}
```
Потім заміни `API_URL` в `index.html` на адресу свого Worker.

---

## Мінімальні вимоги

- Android 5.0+ (API 21)
- ~5 MB вільного місця
- Інтернет для даних (або кеш при офлайн)
