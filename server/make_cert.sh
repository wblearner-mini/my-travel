#!/bin/sh
# Self-signed TLS certificate for this host (no purchase, no public CA). The app trusts it only for
# the addresses below (app/local-res/xml/network_security_config.xml, git-ignored). The key never leaves server/certs/.
# Extra addresses come from the environment, e.g. LAN_IP=192.168.1.20 TAILSCALE_IP=100.x.y.z ./make_cert.sh
set -e
cd "$(dirname "$0")"
mkdir -p certs ../app/src/main/res/raw
umask 077
openssl req -x509 -newkey ec -pkeyopt ec_paramgen_curve:prime256v1 -nodes -days 825 \
  -keyout certs/server.key -out certs/server.crt -subj "/CN=mytravel-local" \
  -addext "subjectAltName=DNS:localhost,IP:127.0.0.1${LAN_IP:+,IP:$LAN_IP}${TAILSCALE_IP:+,IP:$TAILSCALE_IP}" \
  -addext "basicConstraints=critical,CA:TRUE" -addext "keyUsage=critical,digitalSignature,keyCertSign"
cp certs/server.crt ../app/src/main/res/raw/mytravel_server_cert.crt
chmod 644 ../app/src/main/res/raw/mytravel_server_cert.crt
echo "已生成 certs/server.crt（公开证书，已复制进 App）和 certs/server.key（私钥，只留在本机）"
