package io.github.fanqiepi.contextpilot.identity;

import java.util.UUID;

import io.github.fanqiepi.contextpilot.common.InternalServiceException;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class LocalSingleUserSubjectProviderTests {

    @Test
    void resolvesOnlyTheServerConfiguredLocalOwner() {
        AppUserMapper mapper = mock(AppUserMapper.class);
        AppUserEntity user = new AppUserEntity();
        user.setId(UUID.randomUUID());
        user.setUserKey(LocalSingleUserSubjectProvider.LOCAL_OWNER_KEY);
        when(mapper.selectActiveByKey(LocalSingleUserSubjectProvider.LOCAL_OWNER_KEY)).thenReturn(user);

        UserSubject subject = new LocalSingleUserSubjectProvider(mapper).currentSubject();

        assertThat(subject.id()).isEqualTo(user.getId());
        assertThat(subject.key()).isEqualTo(LocalSingleUserSubjectProvider.LOCAL_OWNER_KEY);
        verify(mapper).selectActiveByKey(LocalSingleUserSubjectProvider.LOCAL_OWNER_KEY);
    }

    @Test
    void failsClosedWhenTheSeedSubjectIsUnavailable() {
        AppUserMapper mapper = mock(AppUserMapper.class);

        assertThatThrownBy(() -> new LocalSingleUserSubjectProvider(mapper).currentSubject())
                .isInstanceOfSatisfying(InternalServiceException.class, exception ->
                        assertThat(exception.getCode()).isEqualTo("LOCAL_OWNER_UNAVAILABLE"));
    }
}
