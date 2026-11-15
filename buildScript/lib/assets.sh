#!/bin/bash

set -e

DIR=app/src/main/assets/sing-box
rm -rf $DIR
mkdir -p $DIR
cd $DIR

####
# Жестко задаем проверенные версии, чтобы избежать ошибок API GitHub (Rate Limit / 404)
VERSION_GEOIP="20261112"
echo "VERSION_GEOIP=$VERSION_GEOIP"
echo -n $VERSION_GEOIP > geoip.version.txt
curl -fLSsO https://github.com/SagerNet/sing-geoip/releases/download/$VERSION_GEOIP/geoip.db
xz -9 geoip.db

####
VERSION_GEOSITE="20261115005626"
echo "VERSION_GEOSITE=$VERSION_GEOSITE"
echo -n $VERSION_GEOSITE > geosite.version.txt
curl -fLSsO https://github.com/SagerNet/sing-geosite/releases/download/$VERSION_GEOSITE/geosite.db
xz -9 geosite.db
