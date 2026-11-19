package com.hrms.platform.notification;

public record QueuedNotification(long id, Long recipientEmpId, String recipientEmail, String type,
                                 String subject, String body) {
}
