package com.rootradar.apifd.anomaly.store;

import com.rootradar.apifd.common.ServiceRef;
import com.rootradar.apifd.monitoring.MonitoredService;
import com.rootradar.apifd.monitoring.ServiceRegistry;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.stereotype.Component;

/** Resolves node names to ids and back through the {@link ServiceRegistry} interface (no repository access). */
@Component
public class ServiceLookup {
    private final ServiceRegistry registry;

    public ServiceLookup(ServiceRegistry registry) {
        this.registry = registry;
    }

    /** Immutable view of the registry at one instant; take one per operation. */
    public Snapshot snapshot() {
        return new Snapshot(registry.findAll());
    }

    public static final class Snapshot {
        private final Map<String, MonitoredService> byName = new HashMap<>();
        private final Map<Long, MonitoredService> byId = new HashMap<>();

        Snapshot(List<MonitoredService> all) {
            for (MonitoredService s : all) {
                byName.put(s.name(), s);
                byId.put(s.id(), s);
            }
        }

        public Optional<Long> idOf(String name) {
            return name == null ? Optional.empty() : Optional.ofNullable(byName.get(name)).map(MonitoredService::id);
        }

        public String nameOf(Long id) {
            if (id == null) return null;
            MonitoredService s = byId.get(id);
            return s == null ? null : s.name();
        }

        public ServiceRef refOf(Long id) {
            if (id == null) return null;
            MonitoredService s = byId.get(id);
            return s == null ? new ServiceRef(id, "unknown-" + id) : new ServiceRef(s.id(), s.name());
        }
    }
}
