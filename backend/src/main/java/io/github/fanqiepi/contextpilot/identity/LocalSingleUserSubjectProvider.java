package io.github.fanqiepi.contextpilot.identity;

import io.github.fanqiepi.contextpilot.common.InternalServiceException;
import org.springframework.stereotype.Component;

@Component
public class LocalSingleUserSubjectProvider implements CurrentSubjectProvider {

    public static final String LOCAL_OWNER_KEY = "LOCAL_OWNER";

    private final AppUserMapper appUserMapper;

    public LocalSingleUserSubjectProvider(AppUserMapper appUserMapper) {
        this.appUserMapper = appUserMapper;
    }

    @Override
    public UserSubject currentSubject() {
        AppUserEntity user = appUserMapper.selectActiveByKey(LOCAL_OWNER_KEY);
        if (user == null) {
            throw new InternalServiceException(
                    "LOCAL_OWNER_UNAVAILABLE",
                    "The local owner subject is unavailable",
                    null);
        }
        return new UserSubject(user.getId(), user.getUserKey());
    }
}
