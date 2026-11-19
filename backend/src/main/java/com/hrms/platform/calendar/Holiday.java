package com.hrms.platform.calendar;

import java.time.LocalDate;

public record Holiday(long holidayId, LocalDate holidayDate, String holidayName, String locationCode) {
}
