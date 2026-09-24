package com.acme.hrms.validation.dto.leave;

import com.acme.hrms.validation.meta.FieldMeta;
import jakarta.validation.constraints.Size;

/** Body of POST /api/leave/requests/{id}/approve. */
public class LeaveApproveRequest {

  @Size(max = LeaveText.MAX)
  @FieldMeta(trim = true)
  private String comments;

  public String getComments() {
    return comments;
  }

  public void setComments(String comments) {
    this.comments = LeaveText.blankToNull(comments);
  }
}
