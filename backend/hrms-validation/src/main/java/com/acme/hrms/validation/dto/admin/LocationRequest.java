package com.acme.hrms.validation.dto.admin;

import com.acme.hrms.validation.meta.FieldMeta;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/** Body of POST/PUT /api/admin/locations (LOCATIONS; natural key LOCATION_CODE, immutable). */
public class LocationRequest {

  @NotBlank
  @Size(min = 1, max = 10)
  @Pattern(regexp = AdminRules.CODE_PATTERN)
  @FieldMeta(
      trim = true,
      requiredMessage = "Location code is required",
      patternMessage = AdminRules.CODE_MESSAGE)
  private String locationCode;

  @NotBlank
  @Size(min = 1, max = 100)
  @FieldMeta(trim = true, requiredMessage = "Location name is required")
  private String locationName;

  @Size(max = 200)
  @FieldMeta(trim = true)
  private String addressLine1;

  @Size(max = 200)
  @FieldMeta(trim = true)
  private String addressLine2;

  @Size(max = 100)
  @FieldMeta(trim = true)
  private String city;

  @Size(max = 100)
  @FieldMeta(trim = true)
  private String stateProvince;

  @Size(max = 20)
  @FieldMeta(trim = true)
  private String postalCode;

  @Size(min = 2, max = 3)
  @Pattern(regexp = "^[A-Z]{2,3}$")
  @FieldMeta(trim = true, patternMessage = "Country must be an ISO-3166 alpha-2/3 code")
  private String countryCode;

  @Size(max = 30)
  @Pattern(regexp = AdminRules.PHONE_PATTERN)
  @FieldMeta(trim = true, patternMessage = "Phone must contain 10 or 11 digits")
  private String phoneNumber;

  @Size(max = 50)
  @FieldMeta(trim = true)
  private String timezone;

  @FieldMeta private Boolean activeFlag;

  public String getLocationCode() {
    return locationCode;
  }

  public void setLocationCode(String locationCode) {
    this.locationCode = locationCode == null || locationCode.isBlank() ? null : locationCode.trim();
  }

  public String getLocationName() {
    return locationName;
  }

  public void setLocationName(String locationName) {
    this.locationName = locationName == null || locationName.isBlank() ? null : locationName.trim();
  }

  public String getAddressLine1() {
    return addressLine1;
  }

  public void setAddressLine1(String addressLine1) {
    this.addressLine1 = addressLine1 == null || addressLine1.isBlank() ? null : addressLine1.trim();
  }

  public String getAddressLine2() {
    return addressLine2;
  }

  public void setAddressLine2(String addressLine2) {
    this.addressLine2 = addressLine2 == null || addressLine2.isBlank() ? null : addressLine2.trim();
  }

  public String getCity() {
    return city;
  }

  public void setCity(String city) {
    this.city = city == null || city.isBlank() ? null : city.trim();
  }

  public String getStateProvince() {
    return stateProvince;
  }

  public void setStateProvince(String stateProvince) {
    this.stateProvince =
        stateProvince == null || stateProvince.isBlank() ? null : stateProvince.trim();
  }

  public String getPostalCode() {
    return postalCode;
  }

  public void setPostalCode(String postalCode) {
    this.postalCode = postalCode == null || postalCode.isBlank() ? null : postalCode.trim();
  }

  public String getCountryCode() {
    return countryCode;
  }

  public void setCountryCode(String countryCode) {
    this.countryCode = countryCode == null || countryCode.isBlank() ? null : countryCode.trim();
  }

  public String getPhoneNumber() {
    return phoneNumber;
  }

  public void setPhoneNumber(String phoneNumber) {
    this.phoneNumber = phoneNumber == null || phoneNumber.isBlank() ? null : phoneNumber.trim();
  }

  public String getTimezone() {
    return timezone;
  }

  public void setTimezone(String timezone) {
    this.timezone = timezone == null || timezone.isBlank() ? null : timezone.trim();
  }

  public Boolean getActiveFlag() {
    return activeFlag;
  }

  public void setActiveFlag(Boolean activeFlag) {
    this.activeFlag = activeFlag;
  }
}
