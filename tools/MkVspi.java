package ioniq6.cpclimate;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Build tool (host): writes the complete connection-time description as a binary plist to stdout.
 * Uses the same schema, front comfort options, theme keys and Vehicle page as the runtime channel.
 * The native segment embeds it as a fallback; CpClimate writes the actual settings-specific schema at boot.
 *
 * usage: java ioniq6.cpclimate.MkVspi [model]
 */
public class MkVspi {
    public static void main(String[] a) {
        String model = a.length > 0 ? a[0] : "EV6";
        VehiclePage.tiles = "off";
        VehiclePage page = CarVehicle.describe(new VehiclePage(), model, null, true);
        Map<String, Object> v = new LinkedHashMap<String, Object>();
        v.put("version", CpClimate.VERSION);
        page.update(v);
        VehicleState.page = page;
        VehicleState.variant = "DBX";
        VehicleDescription.seatCooling = true;
        VehicleDescription.wheelHeat = true;
        byte[] complete = new VehicleState(new VsChannel.Log() {
            public void log(String message) { }
        }).vspi();
        System.out.write(complete, 0, complete.length);
    }
}
