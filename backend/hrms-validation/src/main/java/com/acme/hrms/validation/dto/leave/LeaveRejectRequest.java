package com.acme.hrms.validation.dto.leave;

import com.acme.hrms.validation.meta.FieldMeta;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Body of POST /api/leave/requests/{id}/reject; PKG_LEAVE.reject_leave_request p_comments has no
 * default.
 */
public class LeaveRejectRequest {

  @NotBlank
  @Size(min = 1, max = LeaveText.MAX)
  @FieldMeta(trim = true, requiredMessage = "A rejection reason is required")
  private String comments;

  public String getComments() {
    return comments;
  }

  public void setComments(String comments) {
    this.comments = LeaveText.blankToNull(comments);
  }
}
