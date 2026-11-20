package com.hrms.contract;

import static org.assertj.core.api.Assertions.assertThat;

import com.hrms.auth.Action;
import com.hrms.auth.ApiTestSupport;
import com.hrms.auth.AuthController;
import com.hrms.auth.CurrentUser;
import com.hrms.auth.MenuController;
import com.hrms.auth.Module;
import com.hrms.common.ErrorCode;
import com.hrms.leave.LeaveModels;
import com.hrms.performance.PerformanceModels;
import com.hrms.employee.EmployeePage;
import com.hrms.employee.EmployeeView;
import com.hrms.employee.LookupController;
import java.io.IOException;
import java.io.Reader;
import java.lang.reflect.RecordComponent;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.web.servlet.mvc.method.RequestMappingInfo;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;
import org.yaml.snakeyaml.Yaml;

/** Keeps docs/modernization/openapi-v1.yaml and the implemented API in lockstep. */
class OpenApiContractTest extends ApiTestSupport {

    private static final Path CONTRACT = Path.of("..", "docs", "modernization", "openapi-v1.yaml");
    private static final Set<String> METHODS = Set.of("get", "post", "put", "patch", "delete");

    private static Map<String, Object> spec;
    private static String raw;

    @Autowired
    @Qualifier("requestMappingHandlerMapping")
    RequestMappingHandlerMapping mappings;

    @BeforeAll
    static void load() throws IOException {
        raw = Files.readString(CONTRACT);
        try (Reader r = Files.newBufferedReader(CONTRACT)) {
            spec = new Yaml().load(r);
        }
    }

    @Test
    void contractIsV1WithAdditiveAmendments() {
        assertThat((String) spec.get("openapi")).startsWith("3.");
        assertThat((String) map(spec, "info").get("x-contract-version")).matches("v1(\\.\\d+(-P\\d+)?)?");
    }

    @Test
    void everyImplementedRouteIsInTheContractAndViceVersa() {
        Set<String> implemented = new TreeSet<>();
        for (RequestMappingInfo info : mappings.getHandlerMethods().keySet()) {
            for (String path : info.getPatternValues()) {
                if (path.startsWith("/api/")) {
                    info.getMethodsCondition().getMethods()
                            .forEach(m -> implemented.add(m.name() + " " + path));
                }
            }
        }
        Set<String> documented = new TreeSet<>();
        map(spec, "paths").forEach((path, item) -> ((Map<String, Object>) item).keySet().stream()
                .filter(METHODS::contains).forEach(m -> documented.add(m.toUpperCase() + " " + path)));
        assertThat(documented).isEqualTo(implemented);
    }

    @Test
    void responseSchemasMatchTheJavaRecords() {
        Map<String, Class<?>> records = Map.ofEntries(
                Map.entry("LeaveType", LeaveModels.LeaveType.class),
                Map.entry("LeaveBalance", LeaveModels.LeaveBalance.class),
                Map.entry("LeaveRequest", LeaveModels.LeaveRequest.class),
                Map.entry("SubmitLeaveRequest", LeaveModels.SubmitLeaveRequest.class),
                Map.entry("LeaveDecision", LeaveModels.LeaveDecision.class),
                Map.entry("CancelLeaveRequest", LeaveModels.CancelLeaveRequest.class),
                Map.entry("TeamCalendarEntry", LeaveModels.TeamCalendarEntry.class),
                Map.entry("AccrualRunRequest", LeaveModels.AccrualRunRequest.class),
                Map.entry("AccrualRun", LeaveModels.AccrualRun.class),
                Map.entry("Location", LookupController.Location.class),
                Map.entry("LoginRequest", AuthController.LoginRequest.class),
                Map.entry("LoginResponse", AuthController.LoginResponse.class),
                Map.entry("ChangePasswordRequest", AuthController.ChangePasswordRequest.class),
                Map.entry("CurrentUser", CurrentUser.class),
                Map.entry("Menu", MenuController.Menu.class),
                Map.entry("MenuEntry", MenuController.MenuEntry.class),
                Map.entry("EmployeeView", EmployeeView.class),
                Map.entry("EmployeePage", EmployeePage.class),
                Map.entry("Department", LookupController.Department.class),
                Map.entry("JobTitle", LookupController.JobTitle.class),
                Map.entry("ReviewCycle", PerformanceModels.ReviewCycle.class),
                Map.entry("CreateReviewCycle", PerformanceModels.CreateReviewCycle.class),
                Map.entry("PerformanceReview", PerformanceModels.PerformanceReview.class),
                Map.entry("GenerateReviewsResult", PerformanceModels.GenerateReviewsResult.class),
                Map.entry("SelfAssessment", PerformanceModels.SelfAssessment.class),
                Map.entry("ManagerReview", PerformanceModels.ManagerReview.class),
                Map.entry("Acknowledgement", PerformanceModels.Acknowledgement.class),
                Map.entry("RatingBucket", PerformanceModels.RatingBucket.class),
                Map.entry("PerformanceGoal", PerformanceModels.PerformanceGoal.class),
                Map.entry("AddGoal", PerformanceModels.AddGoal.class),
                Map.entry("GoalProgress", PerformanceModels.GoalProgress.class));
        records.forEach((schema, type) -> assertThat(properties(schema))
                .as(schema).isEqualTo(Arrays.stream(type.getRecordComponents())
                        .map(RecordComponent::getName).collect(Collectors.toSet())));
        assertThat(properties("Location")).isEqualTo(Arrays.stream(LookupController.Location.class.getRecordComponents())
                .map(RecordComponent::getName).collect(Collectors.toSet()));
    }

    @Test
    void enumsMatchTheCode() {
        assertThat(enumOf("ErrorCode")).isEqualTo(names(ErrorCode.values()));
        assertThat(enumOf("Module")).isEqualTo(names(Module.values()));
        assertThat(enumOf("Action")).isEqualTo(names(Action.values()));
        assertThat(enumOf("LeaveStatus")).isEqualTo(names(LeaveModels.LeaveStatus.values()));
        assertThat(enumOf("HalfDayPeriod")).isEqualTo(names(LeaveModels.HalfDayPeriod.values()));
        assertThat(enumOf("CycleStatus")).isEqualTo(names(PerformanceModels.CycleStatus.values()));
        assertThat(enumOf("ReviewStatus")).isEqualTo(names(PerformanceModels.ReviewStatus.values()));
        assertThat(enumOf("GoalStatus")).isEqualTo(names(PerformanceModels.GoalStatus.values()));
        assertThat(enumOf("GoalCategory")).isEqualTo(names(PerformanceModels.GoalCategory.values()));
        assertThat(enumOf("ReviewRole")).isEqualTo(names(PerformanceModels.ReviewRole.values()));
    }

    @Test
    void everyReferenceResolves() {
        Matcher m = Pattern.compile("\\$ref: '#/components/(schemas|responses|parameters)/([A-Za-z]+)'").matcher(raw);
        Set<String> missing = new HashSet<>();
        while (m.find()) {
            if (!map(map(spec, "components"), m.group(1)).containsKey(m.group(2))) {
                missing.add(m.group(1) + "/" + m.group(2));
            }
        }
        assertThat(missing).isEmpty();
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> map(Map<String, Object> node, String key) {
        return (Map<String, Object>) node.get(key);
    }

    private static Set<String> properties(String schema) {
        return map(map(map(spec, "components"), "schemas"), schema) == null ? Set.of()
                : map(map(map(map(spec, "components"), "schemas"), schema), "properties").keySet();
    }

    @SuppressWarnings("unchecked")
    private static Set<String> enumOf(String schema) {
        return new HashSet<>((List<String>) map(map(map(spec, "components"), "schemas"), schema).get("enum"));
    }

    private static Set<String> names(Enum<?>[] values) {
        return Arrays.stream(values).map(Enum::name).collect(Collectors.toSet());
    }
}
