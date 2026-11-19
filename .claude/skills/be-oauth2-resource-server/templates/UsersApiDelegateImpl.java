package app.backend.user.api;

import app.backend.api.generated.main.UsersApiDelegate;              // generated from the contract (delegate pattern)
import app.backend.api.generated.main.model.UserResponseDto;         // model record UserResponse + modelNameSuffix Dto
import app.backend.user.domain.UserProfile;
import app.backend.user.service.UserAccessService;
import app.lib.security.AppAuthentication;
import app.lib.security.Roles;
import lombok.RequiredArgsConstructor;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Component;

/**
 * Status endpoint delegate (example: GET /api/users/me, operation getMe, area "main") in backend-service,
 * as in the stack's reference implementation. The status endpoint path and schema come from the API contract
 * (ADR-STACK-00003; reference example: /api/users/me). The generated UsersApiController calls this delegate.
 * Implements pattern (a) default: ENTRY is enough to call it; UserAccessService (which also implements
 * ApplicationAccess) decides from the database and either returns the profile or throws NoAccessException
 * (-> 403 /problems/no-access with reason). In pattern (b) token-roles, the status endpoint returns
 * profile data without application role queries. Its code, tables and SQL: reference.md.
 */
@Component
@RequiredArgsConstructor
public class UsersApiDelegateImpl implements UsersApiDelegate {

    private final UserAccessService userAccessService;
    private final UserApiMapper mapper;                   // MapStruct: UserProfile -> UserResponseDto

    @Override
    @PreAuthorize(Roles.HAS_ENTRY)
    public ResponseEntity<UserResponseDto> getMe() {
        // loadProfile(auth):
        //   auth.noAccessReason() present -> audit an UNPAIRED account (state of the mechanism) with its
        //   sub, throw NoAccessException
        //   otherwise -> person by auth.personId(), its role codes and display permission codes
        UserProfile profile = userAccessService.loadProfile(AppAuthentication.current());
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(mapper.toDto(profile));
    }
}
