#!/usr/bin/env bash
# Builds WaterbucketForwarding.phar from the plugin source next to this script.
# Any PHP 8.x CLI with the phar extension works (PocketMine-MP itself ships PHP 8).
set -euo pipefail
cd "$(dirname "$0")"

if ! command -v php >/dev/null 2>&1; then
    echo "php was not found on the PATH; install PHP 8 or use the prebuilt WaterbucketForwarding.phar" >&2
    exit 1
fi

rm -f WaterbucketForwarding.phar
php -d phar.readonly=0 -d error_reporting=E_ALL -r '
    $phar = new Phar(__DIR__ . "/WaterbucketForwarding.phar");
    $phar->buildFromDirectory(__DIR__ . "/WaterbucketForwarding");
    $phar->setStub($phar->createDefaultStub());
    $phar->setSignatureAlgorithm(Phar::SHA512);
    $phar->compressFiles(Phar::GZ);
    echo "Built " . $phar->getPath() . " (" . iterator_count(new RecursiveIteratorIterator($phar)) . " files)\n";
'
php -r '
    $raw = file_get_contents("phar://WaterbucketForwarding.phar/plugin.yml");
    if (strpos($raw, "name: WaterbucketForwarding") === false) {
        throw new RuntimeException("plugin.yml is missing or wrong inside the phar");
    }
    foreach (["src/Main.php", "src/ForwardingLoginPacketHandler.php", "resources/config.yml"] as $file) {
        if (!file_exists("phar://WaterbucketForwarding.phar/" . $file)) {
            throw new RuntimeException($file . " is missing from the phar");
        }
    }
    echo "phar verified: plugin.yml, sources and config are inside\n";
'
