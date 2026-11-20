package com.hrms.auth;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * Cutover step (runbook §4, OQ-15): start the application once with
 * {@code hrms.cutover.issue-password-resets=true} after the data load to give every ACTIVE employee an
 * unusable credential and a reset link. Repeatable; a second start issues nothing new.
 */
@Component
@ConditionalOnProperty(name = "hrms.cutover.issue-password-resets", havingValue = "true")
class CutoverPasswordResets implements ApplicationRunner {

    static final String ACTOR = "system:cutover";
    private static final Logger LOG = LoggerFactory.getLogger(CutoverPasswordResets.class);

    private final PasswordResetService resets;

    CutoverPasswordResets(PasswordResetService resets) {
        this.resets = resets;
    }

    @Override
    public void run(ApplicationArguments args) {
        PasswordResetService.InitialResets r = resets.issueInitialResets(ACTOR);
        LOG.info("cutover password resets: {} issued, {} active employees skipped (no email)", r.issued(),
                r.skippedWithoutEmail());
    }
}
