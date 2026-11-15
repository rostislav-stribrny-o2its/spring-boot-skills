package app.backend.user.api;

import app.backend.api.generated.main.UsersApiDelegate;   // generated from the contract (delegate pattern)
import app.backend.api.generated.main.model.UserDto;     // name as generated (modelNameSuffix Dto)
import app.backend.user.service.CurrentUserQuery;
import app.lib.security.AppAuthentication;
import app.lib.security.Roles;
import lombok.RequiredArgsConstructor;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Component;

/**
 * SKETCH (backend-service) of GET /api/users/me (operation getMe, area "main"). Path and schema
 * come from the API contract (be-api-contract-first). The generated UsersApiController calls this
 * delegate. The ENTRY role is enough to call it; the query service decides from the database and
 * either returns the read model or throws NoAccessException (-> 403 /problems/no-access with reason).
 * Not yet verified by a build in this repository.
 */
@Component
@RequiredArgsConstructor
public class UsersApiDelegateImpl implements UsersApiDelegate {

    private final CurrentUserQuery currentUserQuery;   // @Transactional(readOnly = true) query service
    private final UserApiMapper mapper;

    @Override
    @PreAuthorize(Roles.HAS_ENTRY)
    public ResponseEntity<UserDto> getMe() {
        // currentUserQuery.me(auth):
        //   auth.noAccessReason() present -> audit an UNPAIRED account with its sub, throw NoAccessException
        //   otherwise -> load identity, role codes and display permissions by auth.personId()
        var me = currentUserQuery.me(AppAuthentication.current());
        return ResponseEntity.ok()
            .cacheControl(CacheControl.noStore())
            .body(mapper.toDto(me));
    }
}
