package com.repolens.service.impl.support;

import com.repolens.config.DependencyCheckProperties;
import com.repolens.domain.entity.DependencyRegistryCacheEntity;
import com.repolens.mapper.DependencyRegistryCacheMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

/**
 * RegistryCacheService 单测（mock mapper，无 DB）。
 */
class RegistryCacheServiceTest {

    private DependencyRegistryCacheMapper mapper;
    private RegistryCacheService service;

    @BeforeEach
    void setup() {
        mapper = mock(DependencyRegistryCacheMapper.class);
        DependencyCheckProperties props = new DependencyCheckProperties();
        props.setCacheTtlDays(7);
        service = new RegistryCacheService(mapper, props);
    }

    @Test
    void get_returns_empty_when_not_cached() {
        when(mapper.findByKey(any(), any())).thenReturn(Optional.empty());
        assertThat(service.get("npm", "lodash")).isEmpty();
    }

    @Test
    void get_returns_entry_when_fresh() {
        DependencyRegistryCacheEntity e = freshEntry("npm", "lodash");
        when(mapper.findByKey("npm", "lodash")).thenReturn(Optional.of(e));
        assertThat(service.get("npm", "lodash")).isPresent();
    }

    @Test
    void get_returns_empty_when_TTL_expired() {
        DependencyRegistryCacheEntity e = freshEntry("npm", "old-pkg");
        e.setCheckedAt(LocalDateTime.now().minusDays(8)); // expired (TTL=7)
        when(mapper.findByKey("npm", "old-pkg")).thenReturn(Optional.of(e));
        assertThat(service.get("npm", "old-pkg")).isEmpty();
    }

    @Test
    void get_returns_entry_when_exactly_at_TTL_boundary() {
        DependencyRegistryCacheEntity e = freshEntry("npm", "boundary");
        e.setCheckedAt(LocalDateTime.now().minusDays(6)); // within TTL
        when(mapper.findByKey("npm", "boundary")).thenReturn(Optional.of(e));
        assertThat(service.get("npm", "boundary")).isPresent();
    }

    @Test
    void put_calls_deleteByKey_then_insert() {
        service.put("npm", "lodash", Boolean.TRUE, null, null);
        verify(mapper).deleteByKey("npm", "lodash");
        verify(mapper).insert((DependencyRegistryCacheEntity) any(DependencyRegistryCacheEntity.class));
    }

    @Test
    void put_sets_all_fields_correctly() {
        service.put("pypi", "requests", Boolean.FALSE, "MAL-1", "CVE-2");
        verify(mapper).insert((DependencyRegistryCacheEntity) argThat(obj -> {
            DependencyRegistryCacheEntity e = (DependencyRegistryCacheEntity) obj;
            return "pypi".equals(e.getEcosystem())
                    && "requests".equals(e.getPackageName())
                    && Boolean.FALSE.equals(e.getExistsFlag())
                    && "MAL-1".equals(e.getMaliciousIds())
                    && "CVE-2".equals(e.getVulnerableIds())
                    && e.getCheckedAt() != null;
        }));
    }

    @Test
    void put_db_failure_is_silent() {
        doThrow(new RuntimeException("DB error")).when(mapper).deleteByKey(anyString(), anyString());
        // Should not throw
        assertThatCode(() -> service.put("npm", "bad", Boolean.TRUE, null, null))
                .doesNotThrowAnyException();
    }

    @Test
    void get_db_failure_returns_empty() {
        when(mapper.findByKey(anyString(), anyString())).thenThrow(new RuntimeException("DB error"));
        assertThat(service.get("npm", "bad")).isEmpty();
    }

    private DependencyRegistryCacheEntity freshEntry(String eco, String pkg) {
        DependencyRegistryCacheEntity e = new DependencyRegistryCacheEntity();
        e.setEcosystem(eco);
        e.setPackageName(pkg);
        e.setExistsFlag(Boolean.TRUE);
        e.setCheckedAt(LocalDateTime.now().minusDays(1));
        return e;
    }
}
