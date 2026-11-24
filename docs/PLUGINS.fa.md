# راهنمای توسعه پلاگین WaterbucketPE (Java، Python و PHP)

این راهنما سیستم پلاگین WaterbucketPE نسخه 1.5.5 را بر اساس پیاده‌سازی واقعی سورس توضیح می‌دهد: نصب، ساختار فایل‌ها، Descriptor، چرخه عمر، Eventها، Commandها، Scheduler، تنظیمات، منابع، کار با Proxy و بازیکنان، Handlerهای Join و Fallback، ارتباط Python و Java، سازگاری، امنیت، کارایی و عیب‌یابی.

- [English](PLUGINS.en.md)
- [فهرست مستندات](PLUGINS.md)
- [نمونه پلاگین Python](../examples/plugins/python-example/)
- [نمونه پلاگین PHP](../examples/plugins/php-example/)
- [پروژه نمونه Java](../examples/plugins/java-example/)

## ۱. انتخاب نوع پلاگین

| مورد | پلاگین Java | پلاگین Python | پلاگین PHP |
|---|---|---|---|
| بسته‌بندی | یک فایل JAR | یک پوشه | یک پوشه |
| نقطه ورود | کلاس Java که Plugin را extend می‌کند | فایل Python مشخص‌شده در main | فایل PHP مشخص‌شده در main |
| Descriptor | فایل waterbucket.yml یا plugin.yml در ریشه JAR | فایل waterbucket.yml یا plugin.yml در پوشه پلاگین | فایل waterbucket.yml یا plugin.yml در پوشه پلاگین |
| Runtime | JVM | GraalPy تعبیه‌شده از طریق GraalVM Polyglot | Quercus تعبیه‌شده (PHP روی JVM) |
| مرحله ساخت | کامپایل و ساخت JAR | ندارد؛ پوشه را کپی می‌کنید | ندارد؛ پوشه را کپی می‌کنید |
| مناسب برای | پردازش پرتکرار، اتصال‌های بزرگ و کنترل کامل JVM | ابزارهای کوچک، اتوماسیون و توسعه سریع | ابزارهای کوچک، یکپارچه‌سازی سبک وب و توسعه سریع |

هر سه نوع توسط PluginManager مدیریت می‌شوند و از Event Manager، Command Map، Scheduler و API جاوای Proxy استفاده می‌کنند.

## ۲. نصب پلاگین

قبل از تغییر فایل‌های پلاگین، Proxy را خاموش کنید. سپس فایل‌ها را در پوشه plugins قرار دهید.

### ساختار Java

~~~text
plugins/
└── ExamplePlugin.jar
~~~

Descriptor باید در ریشه JAR و کلاس main نیز داخل همان JAR باشد.

### ساختار Python

~~~text
plugins/
└── ExamplePython/
    ├── waterbucket.yml
    ├── main.py
    ├── helper.py
    └── config.yml
~~~

Descriptor پلاگین Python باید مستقیماً داخل پوشه پلاگین باشد. مسیر main نسبت به همان پوشه محاسبه می‌شود و فایل‌های Python هم‌سطح main قابل import هستند.

پوشه پلاگین Python تا دو سطح زیر پوشه plugins شناسایی می‌شود؛ برای مثال plugins/ExamplePython یا plugins/category/ExamplePython. فایل Descriptor باید مستقیماً داخل پوشه خود پلاگین باشد. مسیرهای عمیق‌تر در اسکن فعلی پیدا نمی‌شوند.

### ساختار PHP

~~~text
plugins/
└── ExamplePHP/
    ├── waterbucket.yml
    └── main.php
~~~

پوشه‌های پلاگین PHP همان قواعد کشف پلاگین‌های Python را دارند (Descriptor مستقیماً داخل پوشه و مسیر main نسبی به همان پوشه است). برای PHP نیازی به نصب PHP روی سیستم نیست؛ مفسر تعبیه‌شده Quercus داخل خود Proxy ارسال می‌شود.

پس از نصب، Proxy را مجدداً اجرا کنید. در حال حاضر فرمان رسمی برای Hot Reload یا بارگذاری مجدد پلاگین وجود ندارد.

## ۳. فایل Descriptor

هر دو نوع پلاگین از waterbucket.yml یا plugin.yml پشتیبانی می‌کنند. اگر هر دو موجود باشند، waterbucket.yml انتخاب می‌شود.

~~~yaml
name: ExamplePlugin
version: 1.0.0
author: YourName
description: یک پلاگین نمونه WaterbucketPE
main: com.example.waterbucket.ExamplePlugin
depends:
  - AnotherPlugin
~~~

برای Python مقدار main مسیر فایل اسکریپت است:

~~~yaml
name: ExamplePython
version: 1.0.0
author: YourName
description: یک پلاگین نمونه پایتون
main: main.py
depends: []
~~~

### فیلدهای پشتیبانی‌شده

| فیلد | وضعیت | توضیح |
|---|---|---|
| name | ضروری | نام پلاگین و نامی که برای وابستگی‌ها استفاده می‌شود |
| main | ضروری | نام کامل کلاس Java یا مسیر نسبی فایل .py |
| version | پیشنهادی | نسخه نمایشی پلاگین |
| author | پیشنهادی | سازنده پلاگین |
| description | اختیاری | توضیح یک‌خطی پلاگین؛ با `wbplugins verbose` نمایش داده می‌شود |
| depends | اختیاری | فهرست پلاگین‌هایی که باید پیش از این پلاگین فعال شوند |

مدل Descriptor فعلی فقط همین فیلدها را دارد. فیلدهایی مانند api-version، softdepend، load، permissions و website که در Bukkit/Paper دیده می‌شوند، در این Descriptor پیاده‌سازی نشده‌اند.

وابستگی‌ها از نوع اجباری هستند. اگر پلاگین وابسته وجود نداشته باشد، پلاگین فعلی فعال نمی‌شود. زنجیره‌های وابستگی دوری (با هر طولی) شناسایی و رد می‌شوند، بدون اینکه Proxy کرش کند. نام داخل depends باید با نام ثبت‌شده پلاگین مطابقت داشته باشد. سیستم soft dependency مستند و پیاده‌سازی نشده است.

نام پلاگین به نام پوشه داده‌ها و کلید مدیر تبدیل می‌شود، بنابراین فقط می‌تواند شامل حروف، اعداد، فاصله، نقطه، خط تیره و زیرخط باشد (حداکثر ۶۴ نویسه و بدون جداکننده مسیر). Descriptor با نام نامعتبر در زمان بارگذاری رد می‌شود.

### جایگزین به سبک Velocity: انوتیشن @PluginDescriptor

یک پلاگین Java می‌تواند Descriptor را مستقیماً روی کلاس اصلی تعریف کند، به‌جای قرار دادن فایل YAML درون Jar. این همان سبک Velocity است: کلاس انوتیشن‌شده خودِ کلاس اصلی است.

~~~java
import dev.waterbucket.waterbucketpe.plugin.Plugin;
import dev.waterbucket.waterbucketpe.plugin.annotation.PluginDescriptor;

@PluginDescriptor(
        id = "greeter",              // اجباری، شناسه یکتا (نام پوشه داده و هدف depends)
        name = "Greeter",            // اختیاری، نام نمایشی؛ پیش‌فرض همان id
        version = "1.0.0",
        authors = {"YourName"},
        description = "Says hello",
        dependencies = {"AnotherPlugin"}   // اختیاری؛ همان معنای وابستگی اجباری YAML
)
public class GreeterPlugin extends Plugin {
    @Override
    public void onEnable() {
        getProxy().getLogger().info("Hello from " + getName());
    }
}
~~~

قوانین، هم‌راستا با Parser مخصوص YAML:

- id اجباری است و از قوانین نام پلاگین پیروی می‌کند (حروف، اعداد، فاصله، نقطه، خط تیره و زیرخط). این id نام پوشه داده و هدف جست‌وجوی depends می‌شود.
- اگر Jar هم waterbucket.yml یا plugin.yml داشته باشد، YAML برنده است و انوتیشن نادیده گرفته می‌شود؛ پس به‌روزرسانی یک پلاگین موجود نمی‌تواند تصادفی رفتار را تغییر دهد.
- در هر Jar فقط یک کلاس انوتیشن‌شده مجاز است؛ کلاس‌های اضافی گزارش و رد می‌شوند.
- اسکن فقط متادیتا را می‌خواند: static initializer پلاگین هنگام خواندن Descriptor اجرا نمی‌شود.

پلاگین‌های Python تغییری نمی‌کنند و همچنان از waterbucket.yml استفاده می‌کنند.

## ۴. شروع سریع Java

کلاس اصلی باید از کلاس زیر ارث‌بری کند:

~~~java
dev.waterbucket.waterbucketpe.plugin.Plugin
~~~

نمونه:

~~~java
package com.example.waterbucket;

import dev.waterbucket.waterbucketpe.plugin.Plugin;
import dev.waterbucket.waterbucketpe.event.defaults.PlayerChatEvent;
import dev.waterbucket.waterbucketpe.command.Command;
import dev.waterbucket.waterbucketpe.command.CommandSender;
import dev.waterbucket.waterbucketpe.command.CommandSettings;

public final class ExamplePlugin extends Plugin {
    @Override
    public void onStartup() {
        // پس از بارگذاری نمونه پلاگین و پیش از فعال‌سازی اجرا می‌شود.
    }

    @Override
    public void onEnable() {
        getLogger().info("ExamplePlugin enabled");

        getProxy().getEventManager().subscribe(
            PlayerChatEvent.class,
            event -> getLogger().info(
                "{}: {}", event.getPlayer().getName(), event.getMessage()
            )
        );

        getProxy().getCommandMap().registerCommand(new Command(
            "example", CommandSettings.builder()
                .setDescription("Example plugin command")
                .setUsageMessage("/example")
                .build()
        ) {
            @Override
            public boolean onExecute(CommandSender sender, String alias, String[] args) {
                sender.sendMessage("Hello from ExamplePlugin");
                return true;
            }
        });
    }

    @Override
    public void onDisable() {
        // منابعی را که پلاگین ساخته آزاد کنید.
    }
}
~~~

مقدار main در Descriptor باید نام کامل کلاس باشد؛ برای مثال **com.example.waterbucket.ExamplePlugin**. کلاس باید از Plugin ارث‌بری کند و سازنده بدون آرگومان داشته باشد.

### کامپایل در برابر WaterbucketPE

پروژه با Java 17 ساخته می‌شود. برای محیط توسعه محلی، فایل نسخه سازگار WaterbucketPE را در مسیر **libs/Waterbucket.jar** قرار دهید و به‌صورت compile-only به پروژه اضافه کنید:

~~~kotlin
plugins {
    java
}

group = "com.example"
version = "1.0.0"

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(17))
    }
}

dependencies {
    compileOnly(files("libs/Waterbucket.jar"))
}
~~~

خود WaterbucketPE را داخل JAR پلاگین Shade یا Bundle نکنید؛ Proxy API و وابستگی‌های Runtime را فراهم می‌کند. Descriptor باید در ریشه فایل JAR نهایی قرار بگیرد.

## ۵. شروع سریع Python و PHP

پلاگین Python یک پوشه شامل Descriptor و فایل اصلی است. برای اجرای آن نیازی به نصب جداگانه Python روی سیستم نیست؛ Runtime در توزیع Proxy قرار دارد.

Descriptor:

~~~yaml
name: ExamplePython
version: 1.0.0
author: YourName
main: main.py
depends: []
~~~

فایل main.py:

~~~python
import java

PlayerChatEvent = java.type(
    "dev.waterbucket.waterbucketpe.event.defaults.PlayerChatEvent"
)

def on_startup(plugin):
    logger.info("ExamplePython loaded")

def on_enable(plugin):
    plugin.subscribe(PlayerChatEvent, on_chat)

    plugin.register_command(
        "pyhello",
        "Example command from Python",
        "/pyhello",
        None,
        None,
        hello_command
    )

def on_chat(event):
    logger.info(
        "{}: {}",
        event.getPlayer().getName(),
        event.getMessage()
    )

def hello_command(sender, alias, args):
    sender.sendMessage("Hello from Python")
    return True

def on_disable(plugin):
    logger.info("ExamplePython stopping")
~~~

پوشه را در **plugins/ExamplePython/** قرار دهید و Proxy را مجدداً اجرا کنید.

### Import کردن فایل‌های کمکی

پوشه پلاگین به مسیر import پایتون اضافه می‌شود؛ بنابراین فایل‌های هم‌سطح قابل import هستند:

~~~python
# helper.py
def format_greeting(name):
    return "Hello, " + str(name)
~~~

~~~python
# main.py
from helper import format_greeting

def on_enable(plugin):
    logger.info(format_greeting("WaterbucketPE"))
~~~

بهتر است ماژول‌های پلاگین را داخل همان پوشه نگه دارید؛ مگر اینکه عمداً مسیر مشترک Runtime را مدیریت کنید.

### شروع سریع PHP

پلاگین PHP یک پوشه شامل Descriptor و اسکریپت اصلی است. Runtime PHP (یعنی Quercus) داخل Proxy تعبیه شده است و نیازی به نصب PHP خارجی نیست.

Descriptor:

~~~yaml
name: ExamplePHP
version: 1.0.0
author: YourName
main: main.php
depends: []
~~~

اسکریپت اصلی (فایل با تگ معمول `<?php` شروع می‌شود؛ Loader آن را پیش از ارزیابی حذف می‌کند):

~~~php
<?php

function on_startup($plugin) {
    global $logger;
    $logger->info("ExamplePHP loaded");
}

function on_enable($plugin) {
    $plugin->subscribe('PlayerChatEvent', 'on_chat');

    $plugin->registerCommand(
        "phhello",
        "Example command from PHP",
        "/phhello",
        array("phhi"),
        "",
        function ($sender, $alias, $args) {
            $sender->sendMessage("Hello from PHP");
            return true;
        }
    );
}

function on_chat($event) {
    global $logger;
    $logger->info($event->getPlayer()->getName() . ": " . $event->getMessage());
}

function on_disable($plugin) {
    global $logger;
    $logger->info("ExamplePHP stopping");
}
~~~

پوشه را در مسیر **plugins/ExamplePHP/** کپی کنید و Proxy را دوباره راه‌اندازی کنید.

متغیرهای Global در PHP همانند Bridge پایتون هستند: `$plugin` (Facade زیر)، `$proxy` (ProxyServer) و `$logger` (Logger پلاگین). Java Interop از طریق Syntax شیء PHP انجام می‌شود؛ هر متد عمومی Java روی یک شیء متصل را مستقیم صدا بزنید، مثلاً `$proxy->getAllPlayers()` یا `$event->getPlayer()->getName()`.

متدهایی که مستقیماً در PhpPlugin پیاده‌سازی شده‌اند:

| متد | آرگومان‌ها | رفتار |
|---|---|---|
| subscribe | event_class_name، handler | یک Callable پی‌اچ‌پی را با نام کلاس به Event پروکسی متصل می‌کند (نام ساده، مسیر کامل یا کلاس هر پلاگین بارگذاری‌شده) |
| registerCommand | name، description، usage، aliases، permission، handler | یک Command پروکسی ثبت می‌کند؛ Handler برای موفقیت مقدار درست برمی‌گرداند |
| scheduleAsync | handler | کار غیرهمگام را روی Scheduler پروکسی زمان‌بندی می‌کند |
| scheduleDelayed | handler، delay_ticks | کار غیرهمگام تأخیری را زمان‌بندی می‌کند |
| scheduleRepeating | handler، period_ticks | کار غیرهمگام تکرارشونده را زمان‌بندی می‌کند |
| getProxy | ندارد | ProxyServer را برمی‌گرداند |
| getResourceFile | filename | فایلی نسبی به پوشه پلاگین PHP را باز می‌کند |

Hookهای چرخه عمر یعنی `on_startup($plugin)`، `on_enable($plugin)` و `on_disable($plugin)` توابع اختیاری هستند که پس از ارزیابی اسکریپت اصلی با نام پیدا می‌شوند. اشتراک‌های Event، Commandها و Taskهای زمان‌بندی‌شده هنگام غیرفعال‌شدن پلاگین به‌صورت خودکار جدا می‌شوند.

## ۶. چرخه عمر و رفتار هنگام خطا

چرخه بارگذاری به این ترتیب است:

۱. **Discovery:** مدیر پلاگین JARها و پوشه‌های Python و PHP را پیدا می‌کند و Descriptorها را می‌خواند.
۲. **Load:** کلاس اصلی Java ساخته می‌شود؛ اسکریپت Python هنگام مرحله Startup ارزیابی می‌شود.
۳. **Startup:** متد onStartup() در Java، تابع on_startup(plugin) در Python یا تابع on_startup($plugin) در PHP اجرا می‌شود.
۴. **Enable:** متد onEnable()، تابع on_enable(plugin) یا تابع on_enable($plugin) پس از فعال‌شدن وابستگی‌ها اجرا می‌شود.
۵. **Disable:** متد onDisable()، تابع on_disable(plugin) یا تابع on_disable($plugin) هنگام خاموش‌شدن Proxy اجرا می‌شود.

پیاده‌سازی onEnable() در Java اجباری است. Hookهای Python با نام تابع پیدا می‌شوند. در پیاده‌سازی فعلی، نبودن Hook باعث ثبت هشدار در لاگ می‌شود؛ بنابراین هر سه تابع را تعریف کنید، حتی اگر بدنه یکی از آن‌ها خالی باشد.

خطای بارگذاری یا راه‌اندازی یک پلاگین ثبت می‌شود و قرار نیست به‌صورت عمدی بارگذاری همه پلاگین‌های دیگر را متوقف کند. اگر Hook فعال‌سازی شکست بخورد، پلاگین غیرفعال باقی می‌ماند: وضعیت ناموفق به حالت قبل برمی‌گردد، onDisable() برای آن اجرا نمی‌شود و خطا همراه Stack Trace در لاگ ثبت می‌شود. هنگام خاموش‌شدن، پلاگین‌ها به ترتیب معکوس فعال‌سازی غیرفعال می‌شوند تا وابسته‌ها زودتر از وابستگی‌هایشان خاموش شوند.

هنگام Disable (یا خاموش‌شدن Proxy)، هر جا که پیاده‌سازی بتواند امن انجام دهد، Registrationهای پلاگین جدا می‌شوند: برای پلاگین‌های Python و PHP، اشتراک‌های Event حذف، Commandها Unregister و Taskهای زمان‌بندی‌شده لغو می‌شوند؛ همه این‌ها پیش از بسته‌شدن مفسر انجام می‌شود. پلاگین‌های Java باید در onDisable خودشان Handlerهای Event را (با EventManager.unsubscribe) لغو و Commandهایشان را (با CommandMap.unregisterCommand) حذف کنند، چون Handlerهای Java که مستقیم با EventManager ثبت شده‌اند به‌تفکیک پلاگین پیگیری نمی‌شوند.

فراخوانی `PluginManager.disablePlugin(plugin)` در زمان اجرا یک پلاگین و همه پلاگین‌های فعال وابسته به آن را غیرفعال می‌کند (اول وابسته‌ها خاموش می‌شوند و حلقه وابستگی هم باعث توقف بی‌پایان نمی‌شود). غیرفعال‌سازی یک پلاگین، همه Taskهایی که از طریق متدهای زمان‌بندی مخصوص پلاگین (بخش ۹) ساخته شده را نیز لغو می‌کند و برای پلاگین‌های Python و PHP، اشتراک‌ها و Commandها جدا می‌شوند.

PluginManager فعلی یک مدیر چرخه عمر Startup/Shutdown است، نه یک سیستم مدیریت کامل ماژول در زمان اجرا. Hot Reload، جایگزینی زنده و API عمومی Unload در حال حاضر وجود ندارد.

## ۷. سیستم Event

ثبت Event از طریق EventManager مشترک انجام می‌شود.

### ثبت Event در Java

~~~java
import dev.waterbucket.waterbucketpe.event.EventPriority;
import dev.waterbucket.waterbucketpe.event.defaults.ProxyPingEvent;

getProxy().getEventManager().subscribe(
    ProxyPingEvent.class,
    event -> event.setMotd("Welcome to WaterbucketPE"),
    EventPriority.NORMAL
);
~~~

اولویت‌ها به ترتیب **LOWEST**، **LOW**، **NORMAL**، **HIGH** و **HIGHEST** هستند. اگر اولویت مشخص نشود، NORMAL استفاده می‌شود.

### Listener انوتیشنی در Java (@Subscribe)

روش تعریفی به‌جای API تابعی بالا، آشنا برای کاربران Velocity: متدهای public را انوتیشن کنید و کل آبجکت Listener را یک‌جا ثبت کنید.

~~~java
import dev.waterbucket.waterbucketpe.event.Subscribe;
import dev.waterbucket.waterbucketpe.event.defaults.PlayerLoginEvent;

public class LoginListener {

    @Subscribe(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onLogin(PlayerLoginEvent event) {
        event.getPlayer().sendMessage("Welcome!");
    }
}

// ثبت در onEnable:
getProxy().getEventManager().registerListener(this, new LoginListener());
~~~

قوانین و تضمین‌ها:

- متد معتبر باید public و غیر static باشد و دقیقاً یک پارامتر از نوع Event یا زیرکلاس آن بگیرد. امضای غلط با هشداری که نام متد را ذکر می‌کند رد می‌شود.
- priority همان ترتیب LOWEST تا HIGHEST را استفاده می‌کند؛ ignoreCancelled = true یعنی اگر Event از قبل لغو شده باشد (فقط CancellableEvent)، متد اصلاً اجرا نمی‌شود.
- Handler خراب دقیقاً مثل API تابعی ایزوله می‌شود: لاگ می‌شود، رد می‌شود و Pipeline ادامه پیدا می‌کند.
- Listenerهای ثبت‌شده با مالکیت پلاگین هنگام Disable شدن پلاگین به‌طور خودکار جدا می‌شوند (حتی اگر onDisable خطا داده باشد). حذف دستی هر نمونه: unregisterListener(listenerObject)؛ حذف کل مالک: unregisterListeners(this).
- ثبت دوباره همان آبجکت Listener بدون حذف قبلی، IllegalArgumentException می‌دهد؛ تحویل دوباره‌ی Event تقریباً هرگز مورد نظر نیست.
- اشتراک تابعی و انوتیشنی را می‌توان آزادانه ترکیب کرد؛ هر دو همه Eventها را می‌بینند.

پلاگین‌های Python همان Facade پایین را دارند؛ API انوتیشنی مخصوص Java است (انوتیشن قابلیت زبان Java است).

### ثبت Event در Python

~~~python
import java

ProxyPingEvent = java.type(
    "dev.waterbucket.waterbucketpe.event.defaults.ProxyPingEvent"
)

def on_ping(event):
    event.setMotd("Welcome to WaterbucketPE")

def on_enable(plugin):
    plugin.subscribe(ProxyPingEvent, on_ping)
~~~

Facade پایتون فعلاً فقط subscribe(event_class, handler) را ارائه می‌دهد و اولویت NORMAL را استفاده می‌کند. پارامتر اولویت یا Handle برای لغو Subscription در API پایتون وجود ندارد.

### قوانین اجرای Event

- Eventهای همگام روی همان Thread فراخوانی‌کننده اجرا می‌شوند. Handler باید کوتاه باشد؛ اجرای طولانی می‌تواند مسیر شبکه را متوقف کند.
- Eventهایی که با Annotation جاوای @AsyncEvent مشخص شده‌اند از مسیر ناهمگام/قابل‌تکمیل EventManager استفاده می‌کنند. قرارداد دقیق هر Event را از کلاس خودش بخوانید.
- اگر یک Handler در هر دو زبان Exception بدهد، خطا همراه Stack Trace ثبت شده و Handler نادیده گرفته می‌شود؛ بقیه Handlerها و ادامه Event pipeline دست‌نخورده اجرا می‌شوند. یک Handler خراب هرگز باعث Disconnect بازیکن یا توقف Dispatch نمی‌شود.
- لغو (Cancel) یک Event، اجرای Handlerهای بعدی را متوقف نمی‌کند؛ همه Handlerهای ثبت‌شده Event را می‌بینند.
- Dispatch فقط کلاس دقیق Event را تطبیق می‌دهد. اگر روی کلاس والد (مثلاً PlayerEvent یا ProxyPingEvent) Subscribe کنید، Eventهای زیرکلاس (مثلاً ProxyQueryEvent) به شما نمی‌رسند؛ هر کلاس مشخصی را که نیاز دارید جداگانه Subscribe کنید.
- Eventهای قابل لغو، CancellableEvent را پیاده‌سازی می‌کنند. فقط در Eventهایی که این قابلیت را دارند از API لغو استفاده کنید.
- فرض نکنید همه Eventها همگام یا قابل لغو هستند.
- Event Loop مربوط به Netty را با عملیات فایل، دیتابیس، شبکه یا محاسبات طولانی مسدود نکنید.

### لغو اشتراک Event (Java)

Handlerهایی که مستقیم با EventManager ثبت شده‌اند را می‌توان با همان مرجع حذف کرد:

~~~java
import dev.waterbucket.waterbucketpe.event.EventPriority;
import dev.waterbucket.waterbucketpe.event.defaults.ProxyPingEvent;

Consumer<ProxyPingEvent> handler = event -> { };
getProxy().getEventManager().subscribe(ProxyPingEvent.class, handler, EventPriority.NORMAL);
// بعداً، مثلاً در onDisable():
getProxy().getEventManager().unsubscribe(ProxyPingEvent.class, handler);
~~~

تطبیق بر اساس همان نمونه (Instance) Consumer انجام می‌شود، پس مرجعی که ثبت کرده‌اید را نگه دارید. لغو یک Handler ناموجود بی‌اثر است. پلاگین‌های Python به این API نیاز ندارند: اشتراک‌های آن‌ها هنگام Disable به‌طور خودکار جدا می‌شود.

### نمونه کاربردی: رهگیری فرمان‌ها و MOTD

~~~java
import dev.waterbucket.waterbucketpe.event.EventPriority;
import dev.waterbucket.waterbucketpe.event.defaults.PlayerCommandEvent;
import dev.waterbucket.waterbucketpe.event.defaults.ProxyPingEvent;

@Override
public void onEnable() {
    // مسدود کردن یک فرمان پیش از رسیدن به Proxy یا سرور Backend
    getProxy().getEventManager().subscribe(PlayerCommandEvent.class, event -> {
        if (event.getCommand().equalsIgnoreCase("/greed")) {
            event.setCancelled(true);              // فرمان مصرف می‌شود: نه Proxy اجرا می‌کند نه سرور
            event.getPlayer().sendMessage("§cاین فرمان غیرفعال است.");
        }
        // یا بازنویسی فرمان:
        // event.setCommand("/hub");
    });

    // سفارشی‌سازی MOTD و تعداد بازیکنان در لیست سرورها
    getProxy().getEventManager().subscribe(ProxyPingEvent.class, event -> {
        event.setMotd("§bMy Network");
        event.setMaximumPlayerCount(1000);
    });
}
~~~

### کلاس‌های Event داخلی

سورس فعلی این Eventهای پیش‌فرض را دارد:

| Event | کاربرد |
|---|---|
| DispatchCommandEvent | پردازش فرمان |
| FastTransferRequestEvent | درخواست Fast Transfer |
| IncompatibleProtocolEvent | ناسازگاری پروتکل کلاینت |
| InitialServerConnectedEvent | تکمیل اتصال اولیه به Backend |
| InitialServerDeterminedEvent | انتخاب Backend اولیه |
| NetherNetProbeEvent | فرایند Probe در NetherNet |
| NetherNetSignalingEvent | فرایند Signaling در NetherNet |
| NetworkRegisterEvent | ثبت Network Interface |
| PlayerAuthenticatedEvent | احراز هویت بازیکن |
| PlayerChatEvent | پیام چت پیش از ارسال؛ قابل تغییر و لغو |
| PlayerCommandEvent | اجرای هر فرمان توسط بازیکن، پیش از پردازش در Proxy یا Backend؛ فرمان قابل بازنویسی یا لغو است |
| PlayerDisconnectedEvent | قطع اتصال بازیکن |
| PlayerLoginEvent | پیش از اتصال اولیه؛ ناهمگام و قابل لغو |
| PlayerPermissionCheckEvent | بررسی Permission |
| PlayerResourcePackApplyEvent | اعمال Resource Pack |
| PlayerResourcePackInfoSendEvent | ارسال اطلاعات Resource Pack |
| PostTransferCompleteEvent | پس از تکمیل انتقال |
| PreClientDataSetEvent | داده کلاینت پیش از اعمال |
| ProxyPingEvent | Ping لیست سرورها؛ امکان تغییر MOTD و اطلاعات بازیکنان |
| ProxyQueryEvent | پاسخ Query |
| ProxyStartEvent | تکمیل Startup پروکسی |
| ResourcePacksRebuildEvent | بازسازی مجموعه Resource Packها |
| ServerConnectedEvent | اتصال به Backend |
| ServerPreConnectEvent | پیش از باز کردن اتصال Backend؛ ناهمگام و قابل لغو |
| ServerTransferEvent | انتقال بین سرورها |
| ServerTransferFailedEvent | شکست انتقال |
| ServerTransferRequestEvent | درخواست انتقال پیش از شروع اتصال |
| TransferCompleteEvent | تکمیل توالی انتقال/Dimension |

این جدول هدف Eventها را توضیح می‌دهد، نه تمام فیلدها و متدهای آن‌ها را. برای Getter، Setter، قابلیت لغو و Annotationها کلاس مربوطه را در مسیر **src/main/java/dev/waterbucket/waterbucketpe/event/defaults/** بررسی کنید. مرجع نهایی API همان سورس است.

## ۸. Commandها

### Java

از Command ارث‌بری کنید و آن را در CommandMap ثبت کنید:

~~~java
Command command = new Command(
    "hello",
    CommandSettings.builder()
        .setDescription("Sends a greeting")
        .setUsageMessage("/hello")
        .setPermission("example.hello")
        .build()
) {
    @Override
    public boolean onExecute(CommandSender sender, String alias, String[] args) {
        sender.sendMessage("Hello");
        return true;
    }
};

getProxy().getCommandMap().registerCommand(command);
~~~

Executor سه ورودی دریافت می‌کند: sender، نام Alias استفاده‌شده و آرایه آرگومان‌ها. مقدار boolean را مطابق قرارداد Command برگردانید.

### Python

~~~python
def hello(sender, alias, args):
    sender.sendMessage("Hello")
    return True

def on_enable(plugin):
    registered = plugin.register_command(
        "hello",
        "Sends a greeting",
        "/hello",
        ["hi"],
        "example.hello",
        hello
    )
    if not registered:
        logger.warning("The command name is already registered")
~~~

امضای متد:

**register_command(name, description, usage, aliases, permission, handler)**

تابع Handler مقادیر **(sender, alias, args)** را دریافت می‌کند و باید boolean برگرداند. اگر نام فرمان قبلاً ثبت شده باشد، متد false برمی‌گرداند. پیام راهنما را خودتان ارسال کنید؛ برگشت false به‌صورت خودکار پیام Usage ارسال نمی‌کند.

Bridge پایتون Exceptionهای Handler فرمان را دریافت و در لاگ ثبت می‌کند.

## ۹. زمان‌بندی و Tick

هر Tick پروکسی به‌صورت اسمی ۵۰ میلی‌ثانیه است.

### Scheduler در Java

~~~java
var scheduler = getProxy().getScheduler();

var asyncTask = scheduler.scheduleAsync(() -> {
    // کار پس‌زمینه
});

var delayedTask = scheduler.scheduleDelayed(() -> {
    // پس از 20 تیک (تقریباً یک ثانیه)
}, 20);

var repeatingTask = scheduler.scheduleRepeating(() -> {
    // هر 100 تیک
}, 100);

// متدهای Java یک TaskHandler برمی‌گردانند.
// Handle را نگه دارید و در زمان نیاز cancel() را فراخوانی کنید.
repeatingTask.cancel();
~~~

Scheduler از Task همگام، ناهمگام، تأخیری و تکرارشونده پشتیبانی می‌کند. نسخه‌های بدون پارامتر async برای Taskهای تأخیری و تکراری به‌صورت همگام اجرا می‌شوند؛ scheduleAsync ناهمگام است. در صورت نیاز از Overload دارای پارامتر async استفاده کنید.

### Taskهای متعلق به پلاگین (Java)

زمان‌بندی با ارجاع پلاگین، Task را به آن پلاگین متصل می‌کند. هر Task که این‌طور ساخته شود هنگام غیرفعال‌شدن پلاگین به‌طور خودکار لغو می‌شود، پس یک Task تکرارشونده نمی‌تواند از عمر صاحبش بیشتر شود:

~~~java
var scheduler = getProxy().getScheduler();

// هر 40 تیک با تأخیر 20 تیک اجرا می‌شود؛ هنگام Disable پلاگین خودکار لغو می‌شود
scheduler.scheduleRepeating(this, () -> refreshCache(), 20, 40);

// یک‌بار اجرا با تأخیر به نام این پلاگین
scheduler.scheduleDelayed(this, () -> saveAll(), 100);

// یا لغو دستی همه Taskهای این پلاگین، مثلاً در onDisable():
scheduler.cancelTasks(this);

// لغو یک Task مشخص با شناسه‌اش:
scheduler.cancelTask(taskHandler.getTaskId());
~~~

### Scheduler در Python

~~~python
plugin.schedule_async(do_background_work)
plugin.schedule_delayed(run_once, 20)
plugin.schedule_repeating(check_status, 100)
~~~

متدهای کمکی Python کار را به‌صورت ناهمگام زمان‌بندی می‌کنند. این متدها در حال حاضر TaskHandler برنمی‌گردانند؛ بنابراین لغو Task از طریق Facade پایتون امکان‌پذیر نیست. Task تکرارشونده دائمی نسازید، مگر اینکه قرار است تا خاموش‌شدن Proxy فعال بماند. قابلیت Task Handle در آینده را فرض نکنید.

برای عملیات مسدودکننده فایل و شبکه از Task همگام استفاده نکنید.

### زمان‌بندی مبتنی بر زمان واقعی (TaskBuilder به سبک Velocity)

برای زمان‌بندی بر حسب زمان واقعی به‌جای Tick از Builder روان استفاده کنید. این Taskها روی Pool مخصوص زمان‌بند زمانی اجرا می‌شوند و ریتمشان مستقل از Tick Loop است:

~~~java
var scheduler = getProxy().getScheduler();

// یک‌بار پس از ۱۰ ثانیه
scheduler.buildTask(this, () -> announceEvent())
        .delay(10, TimeUnit.SECONDS)
        .schedule();

// هر ۵۰۰ میلی‌ثانیه، اولین اجرا پس از ۲ ثانیه، لغو خودکار هنگام Disable پلاگین
ScheduledTask task = scheduler.buildTask(this, () -> pingDownstreams())
        .delay(2, TimeUnit.SECONDS)
        .repeat(500, TimeUnit.MILLISECONDS)
        .schedule();

// توقف در هر لحظه:
task.cancel();
~~~

معناشناسی:

- repeat = 0 (پیش‌فرض) یعنی Task فقط یک‌بار پس از delay اجرا می‌شود؛ repeat > 0 از معناشناسی fixed-delay استفاده می‌کند: تکرار بعدی یک دوره‌ی کامل پس از پایان تکرار قبلی شروع می‌شود، پس Task کند روی خودش سوار نمی‌شود.
- delay/repeat منفی و واحد زمانی null در همان فراخوانی Builder باعث IllegalArgumentException می‌شود.
- Taskهای ساخته‌شده با پلاگین از همان قرارداد لغو خودکار Taskهای Tick پیروی می‌کنند: cancelTasks(this) روی هر دو API کار می‌کند.
- Exception داخل Task با شماره Task و نام مالک لاگ می‌شود؛ زمان‌بندی باقی می‌ماند و Task تکرارشونده بی‌صدا نمی‌میرد.
- این Taskها همیشه روی Pool زمان‌بند اجرا می‌شوند، هرگز روی Netty Event Loop یا Thread تیک؛ پس فقط از APIهای Thread-Safe پروکسی داخل آن‌ها استفاده کنید.
- Aliasهای سمت ProxyServer: proxy.fire(event) معادل callEvent(event) و proxy.getAllPlayers() معادل getPlayers().values() است.

## ۱۰. کار با Proxy

`getProxy()` نمونه ProxyServer را برمی‌گرداند (`ProxyServer.getInstance()` هم همین کار را می‌کند). این نقطه ورود به همه Managerها و رجیستری بازیکن/سرور است.

### بازیکنان

~~~java
ProxiedPlayer player = getProxy().getPlayer("Steve");          // با نام (اگر آفلاین باشد null)
ProxiedPlayer byId  = getProxy().getPlayer(uuid);              // با UUID
ProxiedPlayer exact = getProxy().getPlayerExact("Steve");      // تطبیق دقیق بدون حساسیت به حروف
java.util.List<ProxiedPlayer> matches = getProxy().matchPlayer("ste"); // تطبیق جزئی نام
java.util.Map<java.util.UUID, ProxiedPlayer> online = getProxy().getPlayers();
~~~

ProxiedPlayer این متدها را در اختیار می‌گذارد: `connect(ServerInfo)`، `sendMessage(String|TextContainer)`، `sendTranslation(...)`، `chat(String)`، `sendPopup`، `sendTip`، `sendTitle`، `sendToastMessage`، `disconnect(reason)`، `sendToFallback(...)`، متدهای Permission (`addPermission`، `hasPermission`، `removePermission`)، اطلاعات دستگاه/پروتکل (`getDevicePlatform`، `getDeviceModel`، `getDeviceId`، `getProtocol`)، شناسه‌ها (`getName`، `getUniqueId`، `getXuid`)، وضعیت شبکه (`getAddress`، `getPing`، `isConnected`، `getServerInfo`، `getDownstreamConnection`) و کنترل Resource Pack (`acceptResourcePacks`). هر بررسی Permission رویداد `PlayerPermissionCheckEvent` را اجرا می‌کند، پس پلاگین‌ها می‌توانند نتیجه را بازنویسی کنند.

### درخواست اتصال به سبک Velocity (ConnectionRequest)

به‌جای صدا زدن connect() و استنتاج نتیجه از Eventها، درخواست اتصال نتیجه‌ی واقعی را برمی‌گرداند:

~~~java
import dev.waterbucket.waterbucketpe.player.ConnectionRequest;
import dev.waterbucket.waterbucketpe.player.ConnectionResult;
import dev.waterbucket.waterbucketpe.player.ConnectionResultStatus;

player.createConnectionRequest(getProxy().getServerInfo("minigames"))
        .connectWithIndication()
        .thenAccept((ConnectionResult result) -> {
            switch (result.getStatus()) {
                case SUCCESS -> { /* اتصال به سرور هدف برقرار شد */ }
                case ALREADY_CONNECTED -> { }
                case CONNECTION_IN_PROGRESS -> { }
                case CONNECTION_CANCELLED -> { /* پلاگین دیگری رویداد Transfer را لغو کرده */ }
                case CONNECTION_FAILED -> {
                    String detail = result.getMessage();  // خطای dial، اعلان Timeout و ...
                }
            }
        });

// بدون مدیریت نتیجه:
player.createConnectionRequest(getProxy().getServerInfo("lobby1")).fireAndForget();
~~~

قرارداد:

- Future همیشه به‌صورت عادی با یک ConnectionResult تمام می‌شود؛ شکست‌ها Status هستند، نه Exception؛ حداکثر ظرف ConnectionRequest.REQUEST_TIMEOUT_SECONDS (۹۰ ثانیه).
- SUCCESS یعنی پروکسی اتصال Downstream را پذیرفته است (Handshake کامل شده). شکست‌های بعدی سرور همچنان از طریق Eventهای Transfer و Fallback معمول گزارش می‌شوند؛ نتیجه‌ی درخواست فقط همان dial درخواستی را پوشش می‌دهد.
- دقیقاً یک Status برنده می‌شود: درخواست دوم برای همان هدف هنگام در جریان بودن dial اول فوراً با CONNECTION_IN_PROGRESS تمام می‌شود؛ پس درخواست‌ها عملاً برای هر بازیکن سریالی هستند.
- Future Thread فراخوان را مسدود نمی‌کند مگر اینکه خودتان .join() بزنید؛ ماشین درخواست چیزی روی مسیر داغ Packet لمس نمی‌کند.
- ALREADY_CONNECTED و CONNECTION_IN_PROGRESS همان شرایطی را بازتاب می‌دهند که connect() خود پروکسی بررسی می‌کند؛ پس Status با کاری که پروکسی واقعاً کرده مطابقت دارد.

### سرورها

~~~java
ServerInfo lobby = getProxy().getServerInfo("lobby1");         // با نام (اگر ثبت نشده باشد null)
ServerInfo byAddr  = getProxy().getServerInfo(address, port);  // با آدرس
java.util.Collection<ServerInfo> servers = getProxy().getServers();
getProxy().registerServerInfo(newServerInfo);                  // ثبت در زمان اجرا
getProxy().removeServerInfo("lobby1");                         // حذف در زمان اجرا
~~~

### حداکثر تعداد بازیکن (Max Players)

مقدار مؤثر از `listener.max_players` در config.yml خوانده می‌شود و در زمان اجرا قابل تغییر است:

~~~java
int max = getProxy().getMaxPlayers();          // اگر Override باشد همان، وگرنه مقدار Config
getProxy().setMaxPlayers(100);                 // تغییر در زمان اجرا + ذخیره در config.yml
getProxy().setMaxPlayers(100, false);          // فقط برای همین اجرا؛ config.yml دست نمی‌خورد
boolean full = getProxy().isFull(player);      // وقتی سقف پر شده و بازیکن اجازه دور زدن ندارد true است
~~~

نکته‌ها:

- `setMaxPlayers(0)` محدودیت را غیرفعال می‌کند. مقدار منفی `IllegalArgumentException` می‌دهد.
- مقدار مؤثر در پاسخ Ping لیست سرورها و Query، نمایش `/wbstatus` و پنل و اجرای محدودیت ورود استفاده می‌شود: وقتی پراکسی پر است، بازیکنان جدید با پیام `waterbucket.server.full` قطع می‌شوند مگر اینکه Permission با نام `waterbucket.bypass.maxplayers` داشته باشند.
- همین قابلیت از کنسول یا داخل بازی با `/wbmaxplayers [count]` هم در دسترس است.
- Handlerهای `PlayerLoginEvent` با اولویت بالاتر بعد از بررسی پر بودن اجرا می‌شوند و در صورت نیاز می‌توانند بازیکن را در همان Handler پذیرش کنند.

### پیام همگانی (Broadcast)

~~~java
getProxy().broadcastMessage("§aنگهداری سرور تا ۵ دقیقه دیگر!");
getProxy().broadcastMessage(new TranslationContainer("waterbucket.server.shutdown"));
~~~

هر دو نسخه پیام را به همه بازیکن‌های آنلاین و کنسول می‌رسانند.

### Managerها

| متد | کاربرد |
|---|---|
| getEventManager() | ثبت/حذف/اجرای Eventها (بخش ۷) |
| getCommandMap() | ثبت و حذف Commandها (بخش ۸) |
| getScheduler() | زمان‌بندی Taskها (بخش ۹) |
| getPluginManager() | یافتن پلاگین‌ها: `getPluginByName`، `getPlugins`، `getPluginMap`، `enablePlugin`، `disablePlugin` |
| getConfiguration() | دسترسی زنده ProxyConfig به مقادیر config.yml |
| getLogger() | لاگر پروکسی |
| getPlayerManager() | رجیستری داخلی بازیکنان |
| getPackManager() | مدیریت Resource Pack |

### Handlerهای Join، Fallback و Forced Host

تصمیم‌های مسیریابی را می‌توان با پیاده‌سازی سفارشی جایگزین کرد:

~~~java
import dev.waterbucket.waterbucketpe.network.connection.handler.IJoinHandler;
import dev.waterbucket.waterbucketpe.network.connection.handler.IReconnectHandler;
import dev.waterbucket.waterbucketpe.network.connection.handler.ReconnectReason;

// بازیکن هنگام ورود به کدام سرور برود
getProxy().setJoinHandler(player ->
    getProxy().getServerInfo("lobby1"));

// وقتی سرور Backend بازیکن را Kick کرد، او به کجا برود (Fallback)
getProxy().setReconnectHandler((player, oldServer, reason, kickMessage) -> {
    if (reason == ReconnectReason.SERVER_KICK && kickMessage.contains("banned")) {
        return null;                     // null یعنی بازیکن از پراکسی قطع می‌شود
    }
    return getProxy().getServerInfo("lobby1");
});
~~~

مقادیر `ReconnectReason` برابرند با `UNKNOWN`، `TIMEOUT`، `EXCEPTION`، `SERVER_KICK` و `TRANSFER_FAILED`. پیاده‌سازی‌های پیش‌فرض همراه پروکسی عرضه می‌شوند (`DefaultJoinHandler`، `DefaultReconnectHandler`، `RoundRobinReconnectHandler`) و مقادیر پیکربندی‌شده از config.yml خوانده می‌شوند. هرگز Handler را null نکنید؛ به‌جایش یک پیاده‌سازی بدون عملیات بدهید. هر دو Handler روی مسیر اتصال اجرا می‌شوند: کوتاه نگهشان دارید و کارهای کند را در Taskهای پس‌زمینه (بخش ۹) انجام دهید.

## ۱۱. تنظیمات و فایل‌های Resource

هر پلاگین یک پوشه داده بر اساس نام کوچک‌شده پلاگین دریافت می‌کند:

~~~text
plugins/
├── ExamplePlugin.jar
└── exampleplugin/
    └── config.yml
~~~

در Java:

~~~java
var config = getConfig();       // در اولین دسترسی config.yml را بارگذاری می‌کند.
String mode = (String) config.get("settings.mode", "safe");
config.set("settings.mode", "fast");
config.save();
~~~

در Python:

~~~python
config = plugin.getConfig()
mode = config.get("settings.mode", "safe")
config.set("settings.mode", "fast")
config.save()
~~~

Configuration از مسیرهای نقطه‌دار برای کلیدهای تو در تو پشتیبانی می‌کند: get(key)، get(key, default)، set(key, value)، remove(key)، exists(key)، getKeys() و save(). فایل با YamlConfig مدیریت می‌شود.

فایل پیش‌فرض **config.yml** را در ریشه پوشه پلاگین Python یا در ریشه JAR قرار دهید. فراخوانی getConfig()/loadConfig() در صورت نبود فایل، Resource پیش‌فرض را به پوشه داده پلاگین کپی و سپس بارگذاری می‌کند. فایل تنظیمات موجود کاربر با کپی پیش‌فرض بازنویسی نمی‌شود. برای دور ریختن وضعیت فعلی و خواندن دوباره config.yml از دیسک، در Java از `reloadConfig()` استفاده کنید.

دسترسی به Resource:

- Java: متد getResourceFile(path) یک InputStream شامل محتوای کامل Resource داخل JAR برمی‌گرداند؛ ورودی‌های ناموجود null برمی‌گردانند (نه Exception). آن را با try-with-resources ببندید.
- Python: متد getResourceFile(path) فایل را نسبت به پوشه پلاگین باز می‌کند؛ مسیرهایی که از پوشه بیرون بزنند (مثلاً با ../) رد شده و null برمی‌گردانند.
- هر دو: متد saveResource(path) فایل Resource را در پوشه داده کپی می‌کند. نسخه دارای source، outputName و replace نام مقصد و رفتار بازنویسی را مشخص می‌کند.

نمونه:

~~~java
saveResource("messages.yml", false);
~~~

~~~python
plugin.saveResource("messages.yml")
~~~

## ۱۲. Runtime پایتون و PHP و Java Interop

پلاگین‌های Python در Runtime تعبیه‌شده GraalVM Polyglot اجرا می‌شوند که همراه توزیع Proxy است. Runtime هنگام ایجاد Context یک پلاگین Python راه‌اندازی می‌شود؛ در نصب‌هایی که فقط پلاگین Java دارند Context پایتون ساخته نمی‌شود.

این متغیرها به‌صورت Global در اختیار هر اسکریپت قرار می‌گیرند:

| Global | توضیح |
|---|---|
| plugin | Facade پلاگین Python جاری |
| proxy | نمونه ProxyServer |
| logger | Logger پلاگین از نوع Log4j |

Java Interop فعال است. با import java و **java.type("fully.qualified.ClassName")** می‌توانید کلاس‌های Java را دریافت کنید. Context با allowAllAccess(true) ساخته می‌شود؛ این قابلیت قدرتمند است و Sandbox امنیتی محسوب نمی‌شود.

متدهایی که مستقیماً در PythonPlugin پیاده‌سازی شده‌اند:

| متد | ورودی | عملکرد |
|---|---|---|
| subscribe | event_class, handler | ثبت Event با اولویت NORMAL |
| register_command / registerCommand | name, description, usage, aliases, permission, handler | ثبت Command |
| schedule_async / scheduleAsync | task | زمان‌بندی ناهمگام |
| schedule_delayed / scheduleDelayed | task, delay_ticks | اجرای ناهمگام پس از تأخیر |
| schedule_repeating / scheduleRepeating | task, period_ticks | اجرای ناهمگام تکرارشونده |
| getProxy | ندارد | دریافت ProxyServer |
| getResourceFile | filename | باز کردن فایل نسبت به پوشه پلاگین Python |

کلاس پایه Plugin نیز متدها و ویژگی‌هایی مانند getName()، getDescription()، getLogger()، getPluginFile()، getDataFolder()، getConfig()، loadConfig()، saveResource()، isEnabled() و getProxy() را ارائه می‌کند.

Facade پایتون افزوده‌ای بر API Java است، نه جایگزین آن. از طریق Interop می‌توان به متدهای عمومی دیگر Java نیز دسترسی داشت؛ اما این به معنی وجود Wrapper ساده Python برای همه متدها نیست.

### نکات سازگاری

- پلاگین Java باید از کلاس Plugin مربوط به WaterbucketPE ارث‌بری کند و در برابر API نسخه مقصد کامپایل شود.
- Package فعلی **dev.waterbucket.waterbucketpe** است. پلاگینی که در برابر Namespace متفاوتی کامپایل شده ممکن است به تغییر سورس و کامپایل مجدد نیاز داشته باشد.
- سازگاری با WaterdogPE برای تمام Binaryهای شخص ثالث تضمین‌شده نیست. هر پلاگین و وابستگی‌هایش را با نسخه فعلی تست کنید.
- Descriptor از **depends** پشتیبانی می‌کند، نه تمام Schema مربوط به Bukkit/Paper.
- API پایتون در حال توسعه است؛ API شبیه Bukkit را بدون وجود پیاده‌سازی فرض نکنید.

## ۱۳. امنیت و کارایی

### امنیت

Contextهای Python با **allowAllAccess(true)** ساخته می‌شوند. بنابراین پلاگین Python می‌تواند به کلاس‌های Java، فایل‌ها، پردازش‌ها، شبکه و سایر قابلیت‌های قابل‌دسترسی برای پردازش Proxy دسترسی داشته باشد. پلاگین‌های Python و Java را کد مورد اعتماد در نظر بگیرید و فقط از منابع مطمئن نصب کنید.

Secretها را در لاگ قرار ندهید و Token یا رمز عبور را در مخزن Commit نکنید. ورودی فرمان‌ها و Eventها را اعتبارسنجی کنید. Python بودن پلاگین به معنی جداسازی امنیتی آن از Proxy نیست.

### کارایی

- برای Eventهای بسیار پرتکرار، منطق نزدیک به Packet و عملیات حساس به Latency، Java را ترجیح دهید.
- Handler همگام باید کوتاه باشد؛ این Handler روی Thread فراخوانی‌کننده اجرا می‌شود.
- عملیات مسدودکننده فایل، شبکه و دیتابیس را به Scheduler منتقل کنید.
- در حلقه‌های پرتکرار از تبدیل‌های سنگین Java/Python خودداری کنید.
- نتیجه java.type را Cache کنید و در هر Event دوباره کلاس را Resolve نکنید.
- Task تکرارشونده و Collection بدون محدودیت نسازید.
- ایجاد Runtime و Context پایتون هزینه حافظه و Startup دارد؛ قابلیت Python هنگام نصب پلاگین Python رایگان نیست.
- همین موضوع درباره PHP هم صادق است: هر پلاگین PHP تا زمانی که فعال است یک محیط Quercus جداگانه دارد و اجرای PHP به‌تفکیک هر پلاگین سری می‌شود. برای کارهای هر Tick یا هر Packet، Java را انتخاب کنید.
- Executorهای داخلی پروکسی (زمان‌بند و Event) اکنون Pool محدود با Thread مقیم ثابت هستند؛ Thread اضافی برای هر Burst ساخته نمی‌شود، پس مصرف RAM و CPU ثابت و صاف می‌ماند. Taskهای خودتان را هم با الگوی همین API بسازید و از ساخت Thread آزاد خودداری کنید.

### محدودیت‌های فعلی چرخه عمر

متدهای زمان‌بندی Python Handle لغو Task برنمی‌گردانند، بنابراین پلاگین Python نمی‌تواند از طریق Facade خودش Taskها را لغو کند. Hot Reload، Unload زمان اجرا و ایزوله‌سازی هر پلاگین وجود ندارد. پلاگین را بر مبنای Reload/Unload زمان اجرا یا چرخه‌های مکرر Enable/Disable طراحی نکنید؛ مدل پشتیبانی‌شده این است که پلاگین یک‌بار هنگام Startup فعال شود و هنگام Shutdown (یا وقتی پلاگین دیگری setEnabled(false) را صدا می‌زند) غیرفعال شود. هنگام Disable پلاگین، اشتراک‌های Event و Commandهای Python به‌طور خودکار جدا می‌شوند؛ منابع بیرونی دیگر (فایل‌ها، اتصال‌ها) را در onDisable خودتان آزاد کنید.

## ۱۴. عیب‌یابی

| مشکل | علت احتمالی | راه بررسی |
|---|---|---|
| پلاگین پیدا نمی‌شود | عمق پوشه یا پسوند اشتباه است | JAR در مسیر پلاگین‌ها؛ Descriptor پایتون مستقیماً داخل پوشه پلاگین |
| هشدار Descriptor | فایل وجود ندارد یا YAML نامعتبر است | waterbucket.yml یا plugin.yml را در ریشه صحیح قرار دهید |
| کلاس اصلی Java بارگذاری نمی‌شود | main اشتباه یا کلاس داخل JAR نیست | نام کامل کلاس و محتوای JAR را بررسی کنید |
| فایل main پایتون پیدا نمی‌شود | مسیر main اشتباه است | مسیر نسبت به پوشه پلاگین و با پسوند .py باشد |
| اسکریپت Python اجرا نمی‌شود | خطای Syntax، Import یا Runtime | Traceback کامل را در لاگ Proxy بررسی کنید |
| ModuleNotFoundError | ماژول در مسیر Import نیست | helper را کنار main.py قرار دهید یا مسیر Runtime سازگار را تنظیم کنید |
| هشدار Hook | تابع وجود ندارد یا قابل اجرا نیست | on_startup، on_enable و on_disable را تعریف کنید |
| Event اجرا نمی‌شود | نام کلاس اشتباه یا پلاگین فعال نشده | مسیر کامل کلاس را با java.type وارد و لاگ Startup را بررسی کنید |
| ثبت Command مقدار false می‌دهد | نام فرمان قبلاً ثبت شده | نام یکتا انتخاب کنید |
| مقدار Config پیدا نمی‌شود | کلید نقطه‌دار یا نوع YAML اشتباه است | config.yml و مقدار پیش‌فرض را بررسی کنید |
| نصب Package پایتون شکست می‌خورد | Package به Native Extension ناسازگار نیاز دارد | سازگاری با GraalPy را بررسی کنید؛ همه Wheelهای CPython قابل استفاده نیستند |
| پلاگین PHP کشف نمی‌شود | main با .php تمام نمی‌شود | مقدار main در Descriptor باید به فایل .php داخل پوشه پلاگین اشاره کند |
| فایل اصلی PHP وجود ندارد | مسیر main اشتباه است | مسیر نسبی به پوشه پلاگین PHP است و باید با .php تمام شود |
| اسکریپت PHP اجرا نمی‌شود | خطای Syntax یا Runtime در main.php | خطای Quercus را در لاگ Proxy بررسی کنید؛ فایل باید با `<?php` شروع شود (Loader آن را حذف می‌کند) |
| Handler رویداد PHP اجرا نمی‌شود | غلط املایی در نام کلاس Event | نام ساده (PlayerChatEvent) یا مسیر کامل کلاس را دقیقاً مطابق Java بنویسید |

## ۱۵. چک‌لیست تست و انتشار

پیش از انتشار پلاگین:

- روی نسخه دقیق WaterbucketPE که هدف گرفته‌اید تست کنید.
- وجود و نبود Dependencyهای اعلام‌شده را آزمایش کنید.
- Hookهای Startup، Enable و Disable را بررسی کنید.
- قابلیت لغو را فقط روی Eventهای قابل لغو تست کنید.
- فرمان را در Console و در صورت امکان از سمت Player آزمایش کنید.
- رفتار Scheduler را زیر بار بررسی کنید و Task تکرارشونده بدون محدودیت نسازید.
- ساخت Config، تغییر آن و ماندگاری پس از Restart را بررسی کنید.
- ورودی نامعتبر و Exceptionهای پلاگین را آزمایش کنید.
- Handler همگام را مسدود نکنید.
- Permissionها و سرویس‌های خارجی موردنیاز پلاگین خود را مستند کنید.

## ۱۶. مراجع سورس API

برای جزئیات نهایی از سورس استفاده کنید:

- کلاس پایه Java: **src/main/java/dev/waterbucket/waterbucketpe/plugin/Plugin.java**
- Descriptor: **src/main/java/dev/waterbucket/waterbucketpe/plugin/PluginYAML.java**
- کشف و چرخه عمر: **src/main/java/dev/waterbucket/waterbucketpe/plugin/PluginManager.java**
- Bridge پایتون: **src/main/java/dev/waterbucket/waterbucketpe/plugin/python/PythonPlugin.java**
- Loader پایتون: **src/main/java/dev/waterbucket/waterbucketpe/plugin/python/PythonPluginLoader.java**
- Bridge پی‌اچ‌پی: **src/main/java/dev/waterbucket/waterbucketpe/plugin/php/PhpPlugin.java**
- Loader پی‌اچ‌پی: **src/main/java/dev/waterbucket/waterbucketpe/plugin/php/PhpPluginLoader.java**
- Event Manager: **src/main/java/dev/waterbucket/waterbucketpe/event/EventManager.java**
- Scheduler: **src/main/java/dev/waterbucket/waterbucketpe/scheduler/WaterbucketScheduler.java**
