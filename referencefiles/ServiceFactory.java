package referencefiles;

import edu.qu.microcluster.services.Base64Service;
import edu.qu.microcluster.services.CsvStatsService;
import edu.qu.microcluster.services.EntropyService;
import edu.qu.microcluster.services.GzipService;
import edu.qu.microcluster.services.HmacService;
import edu.qu.microcluster.services.Service;

import java.util.HashMap;
import java.util.Map;

public class ServiceFactory {
    private static final Map<String, Service> services = new HashMap<>();

    static {
        services.put("BASE64", new Base64Service());
        services.put("GZIP", new GzipService());
        services.put("HMAC", new HmacService());
        services.put("CSV", new CsvStatsService());
        services.put("ENTROPY", new EntropyService());
    }

    public static Service get(String name) {
        if (name == null)
            return null;
        return services.get(name.trim().toUpperCase());
    }
}
