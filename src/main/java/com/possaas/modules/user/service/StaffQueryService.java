package com.possaas.modules.user.service;

import com.possaas.modules.user.dto.*;
import com.possaas.modules.user.repository.*;
import com.possaas.modules.authorization.repository.RoleRepository;
import com.possaas.modules.subscription.dto.PageResponse;
import java.util.*;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class StaffQueryService {
    private final StaffAccessService access;
    private final AdminRestaurantUserQueryRepository queries;
    private final RoleRepository roles;
    private final StaffManagementPolicy policy;

    public PageResponse<StaffResponse> search(AdminRestaurantUserCriteria criteria) {
        var actor = access.requireAccess(false, false);
        return PageResponse.from(queries.search(actor.restaurantId(), criteria).map(StaffResponse::from));
    }

    public StaffResponse detail(UUID id) {
        var actor = access.requireAccess(false, false);
        var user = access.target(actor.restaurantId(), id, false);
        return StaffResponse.from(user, access.role(user));
    }

    public List<StaffRoleResponse> roles() {
        var actor = access.requireAccess(false, false);
        var allowed = policy.assignableRoles(actor);
        return roles.findByRestaurantIdIsNullAndSystemTrueAndActiveTrueOrderByCodeAsc().stream()
            .filter(r -> allowed.contains(r.getCode())).map(StaffRoleResponse::from).toList();
    }
}
