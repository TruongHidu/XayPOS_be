package com.possaas.modules.order.service;

import com.possaas.common.security.CurrentUser;
import com.possaas.modules.order.entity.OrderSubmissionMode;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Component;

@Component
public class OrderSubmissionPolicy {
    public void requireCreate(CurrentUser actor,OrderSubmissionMode mode) {
        require(actor,"ORDER_CREATE");
        if(mode==OrderSubmissionMode.SUBMIT) require(actor,"ORDER_UPDATE");
    }
    public void requireAppend(CurrentUser actor) { requireUpdate(actor); }
    public void requireUpdate(CurrentUser actor) { require(actor,"ORDER_UPDATE"); }
    private void require(CurrentUser actor,String permission) {
        if(!actor.hasPermission(permission)) throw new AccessDeniedException("Required order permission is missing");
    }
}
