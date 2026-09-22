#!/bin/bash
cd /home/ubuntu/repos/ts-plsql-oracle-forms-hrms
export JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64 PATH=/usr/lib/jvm/java-21-openjdk-amd64/bin:$PATH
export HRMS_PG_URL=jdbc:postgresql://localhost:5432/hrms HRMS_PG_USER=hrms HRMS_PG_PASSWORD=hrms
export HRMS_PROXY_CIDRS=127.0.0.1/32,::1/128
export HRMS_FLAG_PAYROLL=NEW HRMS_FLAG_PAYROLL_ENGINE=JAVA HRMS_FLAG_EMPLOYEE=NEW HRMS_FLAG_LEAVE=NEW HRMS_FLAG_PERFORMANCE=NEW
export HRMS_PAYROLL_RECORDED_DIR=/home/ubuntu/repos/ts-plsql-oracle-forms-hrms/tests/golden/payroll
exec java -jar backend/auth/target/auth-0.1.0-SNAPSHOT.jar
