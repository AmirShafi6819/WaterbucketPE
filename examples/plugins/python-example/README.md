# Python plugin example

Copy the entire **python-example** directory into the proxy's **plugins/** directory and rename it if desired.

Expected installed layout:

~~~text
plugins/
└── ExamplePython/
    ├── waterbucket.yml
    ├── main.py
    └── helper.py
~~~

Restart WaterbucketPE. The plugin logs a startup message, subscribes to PlayerChatEvent and registers **/pyhello** (alias **/pyhi**).

This example uses only APIs implemented by the current PythonPlugin facade. It intentionally does not demonstrate hot reload, subscription removal or task cancellation because those APIs are not currently exposed.
