package com.raorbit.opendota.sidecar;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Unit tests for {@link L2Config}'s watched-player parsing ({@link L2Config#parseWatchedPlayers}),
 * the zero-allowing cap resolver ({@link L2Config#resolveCap}), the {@link L2Config.Watched} record's
 * defensive copy, and the legacy constructors defaulting to {@link L2Config.Watched#NONE} (spec §6.5).
 */
class L2ConfigTest {

    private static final String CAP_PROP = "opendota.sidecar.l2.watchedMaxRows";
    private static final String REFETCH_PROP = "opendota.sidecar.l2.watchedRefetchMillis";
    private static final String REFETCH_ENV = "OPENDOTA_SIDECAR_L2_WATCHED_REFETCH_MILLIS";

    @BeforeEach
    @AfterEach
    void clearProperties() {
        System.clearProperty(CAP_PROP);
        System.clearProperty(REFETCH_PROP);
    }

    // ---- parseWatchedPlayers ----

    @Test
    void parsesCommaSeparatedAccountIds() {
        assertThat(L2Config.parseWatchedPlayers("111,222,333"))
                .containsExactly(111L, 222L, 333L);
    }

    @Test
    void trimsAndDedupesPreservingInsertionOrder() {
        // Whitespace around entries is tolerated; a repeat is collapsed; order is insertion order.
        assertThat(L2Config.parseWatchedPlayers("  222 , 111 ,222 , 333 "))
                .containsExactly(222L, 111L, 333L);
    }

    @Test
    void skipsInvalidEntriesButKeepsValidOnes() {
        assertThat(L2Config.parseWatchedPlayers("111,not-a-number,,333"))
                .containsExactly(111L, 333L);
    }

    @Test
    void skipsNonPositiveIdsIncludingTheAnonymizedSentinelZero() {
        // 0 is OpenDota's anonymized-player sentinel and negatives are not valid Steam32 ids; both are
        // dropped so a watched-set can never pin every anonymized match.
        assertThat(L2Config.parseWatchedPlayers("0,-5,111,222"))
                .containsExactly(111L, 222L);
    }

    @Test
    void nullOrBlankYieldsEmptyUnmodifiableSet() {
        assertThat(L2Config.parseWatchedPlayers(null)).isEmpty();
        assertThat(L2Config.parseWatchedPlayers("   ")).isEmpty();
        Set<Long> parsed = L2Config.parseWatchedPlayers("");
        assertThat(parsed).isEmpty();
        assertThatThrownBy(() -> parsed.add(1L)).isInstanceOf(UnsupportedOperationException.class);
    }

    // ---- resolveCap (0 = unlimited; zero-allowing, unlike resolveLong) ----

    @Test
    void resolveCapUnsetIsUnlimitedZero() {
        assumeTrue(System.getenv("OPENDOTA_SIDECAR_L2_WATCHED_MAX_ROWS") == null);
        assertThat(L2Config.resolveCap(CAP_PROP, "OPENDOTA_SIDECAR_L2_WATCHED_MAX_ROWS")).isZero();
    }

    @Test
    void resolveCapKeywordsAreUnlimitedZero() {
        assumeTrue(System.getenv("OPENDOTA_SIDECAR_L2_WATCHED_MAX_ROWS") == null);
        for (String kw : new String[] {"unlimited", "none", "never", "NEVER", "Unlimited"}) {
            System.setProperty(CAP_PROP, kw);
            assertThat(L2Config.resolveCap(CAP_PROP, "OPENDOTA_SIDECAR_L2_WATCHED_MAX_ROWS"))
                    .as("keyword %s", kw).isZero();
        }
    }

    @Test
    void resolveCapPositiveIsUsed() {
        System.setProperty(CAP_PROP, "500");
        assertThat(L2Config.resolveCap(CAP_PROP, "OPENDOTA_SIDECAR_L2_WATCHED_MAX_ROWS")).isEqualTo(500L);
    }

    @Test
    void resolveCapZeroIsUsedAsUnlimited() {
        System.setProperty(CAP_PROP, "0");
        assertThat(L2Config.resolveCap(CAP_PROP, "OPENDOTA_SIDECAR_L2_WATCHED_MAX_ROWS")).isZero();
    }

    @Test
    void resolveCapNegativeOrNonNumericFallsBackToUnlimitedZero() {
        assumeTrue(System.getenv("OPENDOTA_SIDECAR_L2_WATCHED_MAX_ROWS") == null);
        System.setProperty(CAP_PROP, "-5");
        assertThat(L2Config.resolveCap(CAP_PROP, "OPENDOTA_SIDECAR_L2_WATCHED_MAX_ROWS")).isZero();
        System.setProperty(CAP_PROP, "lots");
        assertThat(L2Config.resolveCap(CAP_PROP, "OPENDOTA_SIDECAR_L2_WATCHED_MAX_ROWS")).isZero();
    }

    // ---- watchedRefetchMillis via fromEnvironment() (0 must be reachable; unset → 1h default) ----

    @Test
    void fromEnvironmentRefetchZeroIsReachable() {
        // The documented "re-fetch on every access" value (0) must survive fromEnvironment() — unlike a
        // resolveLong knob, which would reject 0 and coerce it back to the 1h default.
        System.setProperty(REFETCH_PROP, "0");
        assertThat(L2Config.fromEnvironment().watchedRefetchMillis()).isZero();
    }

    @Test
    void fromEnvironmentRefetchPositiveIsUsed() {
        System.setProperty(REFETCH_PROP, "120000");
        assertThat(L2Config.fromEnvironment().watchedRefetchMillis()).isEqualTo(120_000L);
    }

    @Test
    void fromEnvironmentRefetchNegativeFallsBackToDefault() {
        assumeTrue(System.getenv(REFETCH_ENV) == null);
        System.setProperty(REFETCH_PROP, "-5");
        assertThat(L2Config.fromEnvironment().watchedRefetchMillis())
                .isEqualTo(L2Config.DEFAULT_WATCHED_REFETCH_MILLIS);
    }

    @Test
    void fromEnvironmentRefetchNonNumericFallsBackToDefault() {
        assumeTrue(System.getenv(REFETCH_ENV) == null);
        System.setProperty(REFETCH_PROP, "soon");
        assertThat(L2Config.fromEnvironment().watchedRefetchMillis())
                .isEqualTo(L2Config.DEFAULT_WATCHED_REFETCH_MILLIS);
    }

    @Test
    void fromEnvironmentRefetchUnsetIsTheOneHourDefault() {
        assumeTrue(System.getenv(REFETCH_ENV) == null);
        assertThat(L2Config.fromEnvironment().watchedRefetchMillis())
                .isEqualTo(L2Config.DEFAULT_WATCHED_REFETCH_MILLIS);
    }

    // ---- Watched record + legacy constructors ----

    @Test
    void watchedNoneIsEmptyAndUnlimited() {
        assertThat(L2Config.Watched.NONE.accountIds()).isEmpty();
        assertThat(L2Config.Watched.NONE.maxRows()).isZero();
        assertThat(L2Config.Watched.NONE.maxBytes()).isZero();
    }

    @Test
    void watchedRecordCopiesAndIsUnmodifiable() {
        java.util.LinkedHashSet<Long> ids = new java.util.LinkedHashSet<>(Set.of(7L, 9L));
        L2Config.Watched w = new L2Config.Watched(ids, 10, 20);
        // Mutating the source set after construction must not affect the record (defensive copy).
        ids.add(99L);
        assertThat(w.accountIds()).containsExactlyInAnyOrder(7L, 9L);
        assertThatThrownBy(() -> w.accountIds().add(1L)).isInstanceOf(UnsupportedOperationException.class);
        assertThat(w.maxRows()).isEqualTo(10L);
        assertThat(w.maxBytes()).isEqualTo(20L);
    }

    @Test
    void watchedRecordToleratesNullSet() {
        L2Config.Watched w = new L2Config.Watched(null, 0, 0);
        assertThat(w.accountIds()).isEmpty();
    }

    @Test
    void legacyFiveAndSixArgConstructorsDefaultToWatchedNone() {
        Path db = Path.of("ignored.db");
        L2Config five = new L2Config(db, 50_000, 512L * 1024 * 1024, 1000, null);
        assertThat(five.watched()).isEqualTo(L2Config.Watched.NONE);
        assertThat(five.watchedAccountIds()).isEmpty();
        assertThat(five.watchedMaxRows()).isZero();
        assertThat(five.watchedMaxBytes()).isZero();

        L2Config six = new L2Config(db, 50_000, 512L * 1024 * 1024, 1000, null, 4);
        assertThat(six.watched()).isEqualTo(L2Config.Watched.NONE);
    }

    @Test
    void canonicalConstructorExposesTheWatchedBudget() {
        Path db = Path.of("ignored.db");
        L2Config.Watched w = new L2Config.Watched(Set.of(42L), 3, 4096);
        L2Config cfg = new L2Config(db, 50_000, 512L * 1024 * 1024, 1000, null, 4, w);
        assertThat(cfg.watchedAccountIds()).containsExactly(42L);
        assertThat(cfg.watchedMaxRows()).isEqualTo(3L);
        assertThat(cfg.watchedMaxBytes()).isEqualTo(4096L);
    }

    @Test
    void watchedRefetchMillisDefaultsAndIsSettableAndNonNegative() {
        Path db = Path.of("ignored.db");
        // The 7-arg constructor defaults the re-fetch cadence to the built-in default.
        L2Config def = new L2Config(db, 50_000, 512L * 1024 * 1024, 1000, null, 4, L2Config.Watched.NONE);
        assertThat(def.watchedRefetchMillis()).isEqualTo(L2Config.DEFAULT_WATCHED_REFETCH_MILLIS);

        // The 8-arg constructor takes an explicit value; 0 (re-fetch every access) is allowed.
        L2Config zero = new L2Config(db, 50_000, 512L * 1024 * 1024, 1000, null, 4, L2Config.Watched.NONE, 0L);
        assertThat(zero.watchedRefetchMillis()).isZero();

        // A negative value is coerced to 0 rather than rejected.
        L2Config neg = new L2Config(db, 50_000, 512L * 1024 * 1024, 1000, null, 4, L2Config.Watched.NONE, -7L);
        assertThat(neg.watchedRefetchMillis()).isZero();
    }
}
