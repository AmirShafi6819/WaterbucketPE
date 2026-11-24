# PHP plugin example

Copy the entire **php-example** directory into the proxy's **plugins/** directory and rename it if desired.

Expected installed layout:

~~~text
plugins/
└── ExamplePHP/
    ├── waterbucket.yml
    └── main.php
~~~

Restart WaterbucketPE. The plugin logs a startup message, subscribes to PlayerChatEvent and registers **/phhello** (alias **/phhi**).

PHP plugins run on the embedded Quercus runtime - no system PHP installation is required.
