package work.diffdev.ccpython;

import dan200.computercraft.core.apis.http.NettyHttp;
import dan200.computercraft.core.apis.http.options.AddressRule;
import dan200.computercraft.shared.config.ConfigSpec;

import java.lang.reflect.Field;
import java.util.List;

/**
 * CC: Tweaked's own HTTP settings (http.enabled, http.rules), which Python inherits. CC 1.121 moved them from static
 * fields of {@code CoreConfig} into {@link ConfigSpec#getHttpConfig()}; on CC 1.120.x the old fields are read
 * reflectively, so one jar works with both.
 */
final class CCHttpConfig {
    private static final Field LEGACY_ENABLED = legacyField("httpEnabled");
    private static final Field LEGACY_RULES = legacyField("httpRules");

    private CCHttpConfig() {
    }

    static boolean enabled() {
        if (LEGACY_ENABLED != null) {
            try {
                return LEGACY_ENABLED.getBoolean(null);
            } catch (IllegalAccessException e) {
                throw new IllegalStateException(e);
            }
        }
        return current().enabled();
    }

    @SuppressWarnings("unchecked")
    static List<AddressRule> rules() {
        if (LEGACY_RULES != null) {
            try {
                return (List<AddressRule>) LEGACY_RULES.get(null);
            } catch (IllegalAccessException e) {
                throw new IllegalStateException(e);
            }
        }
        return current().addressRules();
    }

    /** CC's defaults (deny $private, allow *) until the config is loaded, and in unit tests without Minecraft. */
    private static NettyHttp.Config current() {
        try {
            return ConfigSpec.getHttpConfig();
        } catch (IllegalStateException | LinkageError e) {
            return NettyHttp.DEFAULT_CONFIG;
        }
    }

    private static Field legacyField(String name) {
        try {
            return Class.forName("dan200.computercraft.core.CoreConfig").getField(name);
        } catch (ReflectiveOperationException e) {
            return null; // CC 1.121+
        }
    }
}
