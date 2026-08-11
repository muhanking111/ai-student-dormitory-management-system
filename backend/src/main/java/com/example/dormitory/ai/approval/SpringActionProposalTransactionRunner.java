package com.example.dormitory.ai.approval;

import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.function.Supplier;

/** Keeps the business write + SUCCEEDED fact atomic, while failure facts use an independent transaction. */
@Component
public final class SpringActionProposalTransactionRunner implements ActionProposalService.TransactionRunner {

    private final TransactionTemplate required;
    private final TransactionTemplate requiresNew;

    public SpringActionProposalTransactionRunner(PlatformTransactionManager transactionManager) {
        required = new TransactionTemplate(transactionManager);
        requiresNew = new TransactionTemplate(transactionManager);
        requiresNew.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    @Override
    public <T> T required(Supplier<T> work) {
        return required.execute(status -> work.get());
    }

    @Override
    public <T> T requiresNew(Supplier<T> work) {
        return requiresNew.execute(status -> work.get());
    }
}
