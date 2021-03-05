/*
 * MIT License
 *
 * Copyright (c) 2020 Airbyte
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in all
 * copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
 * SOFTWARE.
 */

package io.airbyte.scheduler.temporal;

import com.google.common.base.Preconditions;
import io.airbyte.config.IntegrationLauncherConfig;
import io.airbyte.config.JobGetSpecConfig;
import io.airbyte.config.JobOutput;
import io.airbyte.workers.DefaultGetSpecWorker;
import io.airbyte.workers.JobStatus;
import io.airbyte.workers.OutputAndStatus;
import io.airbyte.workers.WorkerConstants;
import io.airbyte.workers.process.AirbyteIntegrationLauncher;
import io.airbyte.workers.process.IntegrationLauncher;
import io.airbyte.workers.process.ProcessBuilderFactory;
import io.airbyte.workers.wrappers.JobOutputGetSpecWorker;
import io.temporal.activity.ActivityInterface;
import io.temporal.activity.ActivityMethod;
import io.temporal.activity.ActivityOptions;
import io.temporal.workflow.Workflow;
import io.temporal.workflow.WorkflowInterface;
import io.temporal.workflow.WorkflowMethod;
import java.nio.file.Path;
import java.time.Duration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;

@WorkflowInterface
public interface SpecWorkflow {

  @WorkflowMethod
  JobOutput run(IntegrationLauncherConfig launcherConfig);

  class WorkflowImpl implements SpecWorkflow {

    ActivityOptions options = ActivityOptions.newBuilder()
        .setScheduleToCloseTimeout(Duration.ofMinutes(2)) // todo
        .build();

    private final SpecActivity activity = Workflow.newActivityStub(SpecActivity.class, options);

    @Override
    public JobOutput run(IntegrationLauncherConfig launcherConfig) {
      return activity.run(launcherConfig);
    }

  }

  @ActivityInterface
  interface SpecActivity {

    @ActivityMethod
    JobOutput run(IntegrationLauncherConfig launcherConfig);

  }

  class SpecActivityImpl implements SpecActivity {

    private static final Logger LOGGER = LoggerFactory.getLogger(SpecActivityImpl.class);

    private final ProcessBuilderFactory pbf;
    private final Path workspaceRoot;

    public SpecActivityImpl(ProcessBuilderFactory pbf, Path workspaceRoot) {
      this.pbf = pbf;
      this.workspaceRoot = workspaceRoot;
    }

    public JobOutput run(IntegrationLauncherConfig launcherConfig) {
      try {
        // todo (cgardens) - there are 2 sources of truth for job path. we need to reduce this down to one,
        // once we are fully on temporal.
        final Path jobRoot = workspaceRoot
            .resolve(String.valueOf(launcherConfig.getJobId()))
            .resolve(String.valueOf(launcherConfig.getAttemptId().intValue()));

        MDC.put("job_id", String.valueOf(launcherConfig.getJobId()));
        MDC.put("job_root", jobRoot.toString());
        MDC.put("job_log_filename", WorkerConstants.LOG_FILENAME);

        final IntegrationLauncher integrationLauncher =
            new AirbyteIntegrationLauncher(launcherConfig.getJobId(), launcherConfig.getAttemptId().intValue(), launcherConfig.getDockerImage(), pbf);

        final JobGetSpecConfig jobGetSpecConfig = new JobGetSpecConfig().withDockerImage(launcherConfig.getDockerImage());
        final OutputAndStatus<JobOutput> run = new JobOutputGetSpecWorker(new DefaultGetSpecWorker(integrationLauncher))
            .run(jobGetSpecConfig, jobRoot);
        if (run.getStatus() == JobStatus.SUCCEEDED) {
          Preconditions.checkState(run.getOutput().isPresent());
          return run.getOutput().get();
        } else {
          throw new TemporalJobException();
        }
      } catch (Exception e) {
        throw new RuntimeException("Spec job failed with an exception", e);
      }
    }

  }

}