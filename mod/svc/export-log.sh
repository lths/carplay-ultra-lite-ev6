#!/system/bin/sh
# Root helper, called every ten seconds. Export only this mod's climate/video/media diagnostics,
# excluding receiver handshakes and unrelated mods. hmgapid usb <id> returns one path per line.
[ -n "$HMGAPI_DATA" ] || exit 1
hmgapid usb cp-climate > "$HMGAPI_DATA/diagnostic-usb-paths"
[ -s "$HMGAPI_DATA/diagnostic-usb-paths" ] || exit 0
{
    echo "CarPlay Ultra-Lite EV6 diagnostics (recent saved HMG-API log)"
    hmgapid log | grep -E '\[cp-climate\] (CAR |VEH |VS phone set |VS phone may not write |VS OPACK |VIDEO |STOCK |MEDIA )'
} > "$HMGAPI_DATA/diagnostic.tmp"
if grep -q '\[cp-climate\]' "$HMGAPI_DATA/diagnostic.tmp"; then
    mv "$HMGAPI_DATA/diagnostic.tmp" "$HMGAPI_DATA/ev6-carplay.log"
fi
[ -f "$HMGAPI_DATA/ev6-carplay.log" ] || exit 0
while IFS= read -r folder; do
    [ -d "$folder" ] || continue
    cp "$HMGAPI_DATA/ev6-carplay.log" "$folder/ev6-carplay.log"
done < "$HMGAPI_DATA/diagnostic-usb-paths"
