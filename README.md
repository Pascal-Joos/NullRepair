# NullRepair

NullRepair is an LLM-based tool that automatically repairs nullability errors reported by [NullAway](https://github.com/uber/NullAway) based on safe usage regions. It is built on top of [NullAwayAnnotator](https://github.com/nimakarimipour/NullAwayAnnotator).

The preprint [LLM-Based Repair of Static Nullability Errors](https://arxiv.org/abs/2507.20674) describes the tool and the conducted experiments in detail.

Please cite the paper if you use NullRepair in your research:

```bibtex
@misc{karimipour2026llmbasedrepairstaticnullability,
      title={LLM-Based Repair of Static Nullability Errors}, 
      author={Nima Karimipour and Pascal Joos and Michael Pradel and Martin Kellogg and Manu Sridharan},
      year={2026},
      eprint={2507.20674},
      archivePrefix={arXiv},
      primaryClass={cs.SE},
      url={https://arxiv.org/abs/2507.20674}, 
}
```

## 1. Getting Started

### 1.1. Requirements

The VS Code Dev Container extension with VS Code.  
Tested with Docker 29.4.1
OpenAI API key (for running NullRepair and the baselines, not needed for reproducing the experiment results from the log files).

### 1.2. Installation using VS Code Dev Container

Don't open the VS Code Dev Container before completing the steps 1 to 3.

1. Clone this repository using ssh.

2. Run: ```git submodule update --init --recursive``` to initialize the mini-swe-agent submodule.

3. Run: `bash checkout_benchmarks.sh`.  
This clones the target projects for the experiment into `benchmarks`.  
Refer to these folders for any commits created by NullRepair.

4. Reopen the project in a devcontainer using the VSCode Dev Container extension. All needed dependencies and setup steps are then executed automatically. Wait until the postcreatecommand finishes executing and the terminal is ready to use.

5. Activate the Python environment by running (if not yet active):  
```source .venv/bin/activate```

6. Configure the OpenAI API key by running the script `set_openai_key.py` and pasting the key when prompted. This will write the API key to the mini-SWE-agent configuration file and add it to a .env file.  
This is needed to run NullRepair and the baselines, which use the OpenAI API.  
For a lightweight reproduction of the experiment results from the log files, the API key is not needed.  
```python3 set_openai_key.py```

### 1.3. Smoke Test (Testing the installation)

Run the smoke test to verify that all components are correctly installed and functional.  
**No OpenAI API key is required.**  
The test checks Java, the NullRepair JAR, the benchmark projects, the Python packages, and runs the full NullRepair pipeline in disabled mode (static analysis only, no LLM call). It takes roughly 2-3 minutes.

```bash
python3 smoke_test.py
```

Expected output:

```text
============================================================
  1/3  Prerequisites
============================================================
  [PASS] Java is available
         (openjdk version "21.0.10" 2026-01-20)
  [PASS] NullRepair JAR is built
  [PASS] Benchmark projects are checked out

============================================================
  2/3  Python evaluation packages
============================================================
  [PASS] pandas, numpy, scikit-learn are importable

============================================================
  3/3  NullRepair end-to-end pipeline (no LLM)
============================================================
  Running NullRepair on one error of 'eureka' in disabled mode.
  Exercises: NullAway static analysis, build, annotation injection,
  and git integration — no API call is made.

  $ java -jar annotator-core/build/libs/annotator-core-1.3.20-SNAPSHOT.jar eureka --mode disabled --selectedErrorIds 2 --depth 1

ANNOTATOR VERSION: 3, BUILD: 6
Received arguments: eureka, --mode, disabled, --selectedErrorIds, 2, --depth, 1
Running eureka benchmark in disabled mode.
Resolve remaining errors mode: DISABLED
Selected error IDs: [2]
Configuring logging for benchmark: eureka, branch: joos/disabled-3
Root path for logs: /home/vscode/NullRepair/evaluation_data/logs/eureka/disabled-3
Running on branch name: joos/disabled-3
Starting annotator...
Preprocessing...
Annotating...true
Max Depth level: 1
Analyzing at level 1, Scheduling for: 5 builds for: 14 fixes

Processing  20% [============>                                      ] 1/5 (0:00:00 / 0:00:00)

...

Processing 100% [===================================================] 5/5 (0:00:11 / 0:00:00)
Finished annotating.
Commiting changes to branch joos/disabled-3...

  [PASS] Pipeline runs end-to-end

============================================================
  SMOKE TEST PASSED — NullRepair is correctly installed.
============================================================
```

### 1.4 Quick Run of NullRepair

**This requires an OpenAI API key** to be set up as described in 1.3., as NullRepair queries the OpenAI API to generate fixes.

A small example run where NullRepair is run on three nullability errors of project eureka can be executed with the following command:  
```java -jar annotator-core/build/libs/annotator-core-1.3.20-SNAPSHOT.jar eureka --mode advanced --selectedErrorIds 2,4,5```

Expected output (truncated):  

```text
ANNOTATOR VERSION: 3, BUILD: 6
Received arguments: eureka, --mode, advanced, --selectedErrorIds, 2,4,5
Running eureka benchmark in advanced mode.
Resolve remaining errors mode: ADVANCED
Selected error IDs: [2, 4, 5]
Configuring logging for benchmark: eureka, branch: joos/advanced-3
Root path for logs: /home/vscode/NullRepair/evaluation_data/logs/eureka/advanced-3
Running on branch name: joos/advanced-3
Starting annotator...
Preprocessing...
Annotating...false
Loading cache...
Loaded 0 entries from cache.
Max Depth level: 1
Analyzing at level 1, Scheduling for: 5 builds for: 14 fixes
Processing 100% [===============================================================================================================================================] 5/5 (0:00:18 / 0:00:00) 
2 : TOP LEVEL CALL TO FIX ERROR: Type='METHOD_NO_INIT', message='initializer method does not guarantee @NonNull field serverConfig (line 106) is initialized along all control-flow paths (remember to check for exceptions or early returns).'
/home/vscode/benchmarks/eureka/eureka-core/src/main/java/com/netflix/eureka/RateLimitingFilter.java:114
  public RateLimitingFilter() {}
Resetting NullAwayCodeFix state.
Sending request to OpenAI...
Response received from OpenAI.
Token usage - Uncached Prompt: 406, Cached Prompt: 0, Completion: 497, Total: 903
Cached response
Finished processing.
Time taken to fix error: 3713 ms

>>> PROGRESS [======              ] 1 / 3 (33%) <<<

Writing log to file...
Logging ChatGPT token usage...
Calculating run metrics...
Running tests...
Trying to commit changes...
Commiting changes...
4 : TOP LEVEL CALL TO FIX ERROR: Type='DEREFERENCE_NULLABLE', message='dereferenced expression resourceRecordSetWithHostedZone is @Nullable'
/home/vscode/benchmarks/eureka/eureka-core/src/main/java/com/netflix/eureka/aws/Route53Binder.java:277
      resourceRecordSetWithHostedZone
...
```

Logs are then located at `evaluation_data/logs/eureka/advanced-3` and the changes made by NullRepair are committed to the branch `joos/advanced-3` in the target project repository at `benchmarks/eureka`.

## 2. Inspecting Logs and Evaluation Results

Correspondence of approach names in the paper and the repository:

| Paper name | Repository name |
| --- | --- |
| NullRepair | advanced |
| SinglePrompt baseline | basic |
| mini-SWE-agent baseline | agent_baseline |

### 2.1. Logs

See `evaluation_data/logs` for the logs of executed runs and `benchmarks` for the target projects.  
The log files are organized by project and by experiment configuration (used approach and per-patch/combined mode). Each run creates a new log folder.  
For example, for the run on eureka of NullRepair in per-patch mode, refer to [evaluation_data/logs/eureka/advanced-evaluation-run-gpt5.1](evaluation_data/logs/eureka/advanced-evaluation-run-gpt5.1) for the logs of the run.  

The logs are structured as follows:

- `app.log` contains the complete execution log of the run.
- `log-<errorID>.log` contains the execution log for the specific errorID.
- `test-log-<errorID>.log` contains the log of the test execution after fixing the specific errorID.
- `metrics.tsv` contains the metrics on fix success for each error of the run.
- `token_usage.tsv` contains token usage information for each error of the run.
- `commits.tsv` contains the commit information for each error of the run, where a fix was created.
- `timers.tsv` logs the end-to-end time taken for the run.

For the created fixes, you can check the commit history of the respective run's branch (`joos/<log-folder-name>`) in the target project repository (e.g., for NullRepair per-patch on eureka it is the branch `joos/advanced-evaluation-run-gpt5.1` of `benchmarks/eureka`).  
Each fix made by NullRepair is committed separately with a commit message that includes the error ID and the error message and is then reverted in a subsequent commit (for the per-patch mode).  

### 2.2. Aggregated Evaluation Results

Aggregated stats on the runs, plots, and manual inspection results can be found in `evaluation_data/evaluation_results`, organized as follows:

- `per_patch/` **Corresponds to: RQ1-C1 (Table 2), RQ2 (Table 4)**  
per-patch level results of the different approaches. Shows success rates, failing tests, token usage, and timing per benchmark project and as total.
- `combined/` **Corresponds to: RQ1-C1 (Table 2), RQ1-C2 (Table 3)**  
combined level results of the different approaches. Shows success rates, failing tests, token usage, and timing per benchmark project and as total.
- `manual_inspection/` **Corresponds to: RQ1-C3**  
the 75-sample manual inspection dataset, per-reviewer initial scores, the consolidated scoring file (`manual_inspection_scoring_with_classification.tsv`), and derived statistics.
The scoring file [manual_inspection_scoring_with_classification.tsv](evaluation_data/evaluation_results/manual_inspection/manual_inspection_scoring_with_classification.tsv) is most relevant for reproducing and validating the manual inspection results. It contains for all 75 samples the full reasoning of the reviewers' initial scoring, subsequent consolidation discussions, and the final consolidated scores.
Import it into Google Slides, Excel, or similar for better readability.
- `venn_diagrams/` **Not included in the accepted paper version**  
Venn diagrams showing overlap in resolved errors, resolved errors with no failing tests, and in manual inspection scores, across approaches.
- `stats_excluding_preliminary_study_projects/` **Corresponds to: Threats to Validity**  
results with the three preliminary-study projects (conductor, litiengine, retrofit) excluded.

### 2.3. Mapping of paper table metrics to stat-file fields

The TSV files contain all raw numbers used in the paper. The tables below map each paper column to the corresponding stat-file field, so the numbers can be directly verified.

**Table 2 — per-patch mode (RQ1-C1)** (files: `evaluation_results/per_patch/evaluation_stats_<approach>_per_patch_evaluation_gpt5.1.tsv` (separated by approach)):

| Paper column | Stat-file field | Notes |
| --- | --- | --- |
| Errors | `total_target_errors` | |
| G (generated, no compile errors) | `generated_patches_no_compilation_errors` | = `generated_patches` − `error_introducing_patches` |
| R (resolved, no new errors) | `resolving_patches_and_no_new_errors` | **Not** `resolving_patches_incl_new_errors`, which also counts patches that resolved the target error but introduced a new one |
| TE (triggered new nullability error) | `trigger_new_error_patches` | |

**Table 2 — combined mode (RQ1-C1)** (files: `evaluation_results/combined/evaluation_stats_<approach>_combined__evaluation_gpt5.1.tsv` (separated by approach)):

| Paper column | Stat-file field | Notes |
| --- | --- | --- |
| Errors | `total_target_errors` | |
| R (resolved when selectively applied) | `resolved_target_errors` | = `total_target_errors` − `remaining_errors` |

**Table 3 — test failures in combined mode (RQ1-C2)** (same combined stat files):

| Paper column | Stat-file field | Notes |
| --- | --- | --- |
| Number of Failing Unit Tests | `total_test_failures` |  |

**Manual inspection results — (RQ1-C3)**:

Per-approach (tool) score counts and averages come from `evaluation_results/manual_inspection/scoring_stats/manual_inspection_statistics.tsv` (approach names are prefixed with `hash_`):

| Paper text | Stat-file field | Value for NullRepair (`hash_advanced`) |
| --- | --- | --- |
| Likely acceptable (score 1) | `Count_Score_1` | 29 (39%) |
| Needs work (score 2) | `Count_Score_2` | 16 (21%) |
| Likely unacceptable (score 3) | `Count_Score_3` | 30 (40%) |
| Average score | `Average_Score` | 2.01 |
| Overall wins (outperforms both baselines) | `Overall_Wins` | 28 |
| Overall ties (tied for best with ≥ 1 baseline) | `Overall_Ties` | 26 |
| Overall losses (outperformed by any baseline) | `Overall_Losses` | 21 |

The `Correctness of Classification` metrics from the paper are based on the column `Correctness of classification by NullRepair` in `evaluation_data/evaluation_results/manual_inspection/manual_inspection_scoring_with_classification.tsv`.

**Table 4 — efficiency metrics (RQ2)** (files: `evaluation_results/per_patch/evaluation_stats_<approach>_per_patch_evaluation_gpt5.1.tsv` (separated by approach)):

The paper reports per-project and per-error averages; the stat files store totals and per-error averages.

Refer to the last row of the per-patch stat files for the totals across all projects, and the `avg_` fields for the per-error averages.

| Paper metric | Stat-file field | Conversion |
| --- | --- | --- |
| Time (min) (per error) | `avg_execution_time_sec` | ÷ 60 to get minutes |
| Number of Prompts (per error) | `avg_agent_cycles` | Direct |
| Total Tokens (k) (per error) | `avg_tokens` | ÷ 1000 |
| Cost (USD) (per error) | `avg_monetary_cost` | Direct |

## 3. Reproduce Tables and Figures in the Paper (Short-Hand Reproduction of RQ1 and RQ2)

Pre-computed results are already present in `evaluation_data/evaluation_results/`. To recompute them from the log files, run the single wrapper script from the repository root:

```bash
python3 reproduce_results.py
```

Reproduced output files are written with a `_reproduced` suffix, so they sit alongside the originals without overwriting them.

Refer to the mapping of paper table metrics to stat-file fields in section 2.3. to verify the numbers in the paper against the reproduced stat files.

This script runs the following steps in order:

1. **Evaluation statistics** (`evaluation_scripts/calculate_evaluation_stats.py`):
  
    **Corresponds to: RQ1-C1 (Table 2), RQ1-C2 (Table 3), RQ2 (Table 4)**  
    aggregates per-error metrics (total generated patches, total resolved errors, failing tests, token usage, cost) for all six experiment configurations (NullRepair / SinglePrompt / mini-SWE-agent × per-patch / combined). Outputs six TSV files to `evaluation_data/evaluation_results/per_patch/` and `evaluation_data/evaluation_results/combined/` with names such as `evaluation_stats_advanced_per_patch_reproduced.tsv`.

2. **Patch file-count statistics** (`evaluation_scripts/patch_file_count_stats.py`):  

    analyses how many Java files each generated patch touches, broken down by approach and outcome. Prints a summary table and writes `evaluation_data/evaluation_results/per_patch/patch_file_count_stats_reproduced.csv`.

3. **Manual inspection score analysis** (`evaluation_scripts/manual_inspection/analyze_manual_inspection_scores.py`):  

    **Corresponds to: RQ1-C3**  
    reads the consolidated 75-sample manual inspection file and computes per-tool score distributions, win/loss/tie counts, and pairwise matchup tables. Outputs `evaluation_data/evaluation_results/manual_inspection/scoring_stats/manual_inspection_statistics_reproduced.tsv` and a `_reproduced_pairwise.tsv` companion.

4. **Inter-rater agreement** (`evaluation_scripts/manual_inspection/calculate_inter_rater_agreement.py`):  

    **Additional analysis for RQ1-C3**  
    computes Cohen's Kappa across the three reviewer pairs over all scored patches and writes the full report to `evaluation_data/evaluation_results/manual_inspection/scoring_stats/agreement_analysis_reproduced.txt`.

Two additional kinds of figures can be created using Jupyter:

- **Venn diagrams**:  

    open and run `evaluation_scripts/create_venn_diagrams.ipynb`.

- **Manual inspection score plot**:  

    **Corresponds to: RQ1-C3 (Figure 7)**  
    open and run `evaluation_scripts/manual_inspection/manual_inspection_plot.ipynb`.

## 4. Run a Large-Scale Experiment (Complete Reproduction of RQ1-C1, RQ1-C2, and RQ2)

Follow the installation steps in 1.3. and then run one of the following commands to run a large-scale experiment on a target project.  
Run either NullRepair (advanced), the SinglePrompt baseline (basic), or the mini-SWE-agent baseline (agent_baseline).  
Per default the project is reset for each error (patch-level analysis). Set `--combined` to stack successful error patches (aggregate-level analysis).  

The following commands run the experiment on project eureka.  

Run NullRepair on project eureka:  
```java -jar annotator-core/build/libs/annotator-core-1.3.20-SNAPSHOT.jar eureka --mode advanced```

Run SinglePrompt baseline on project eureka:  
```java -jar annotator-core/build/libs/annotator-core-1.3.20-SNAPSHOT.jar eureka --mode basic```

Run mini-SWE-agent baseline on project eureka:  
```java -jar annotator-core/build/libs/annotator-core-1.3.20-SNAPSHOT.jar eureka --mode agent_baseline```

List of all projects: `conductor`, `eureka`, `glide`, `gson`, `jadx`, `libgdx`, `litiengine`, `mockito`, `retrofit`, `spring-boot`, `wala-util`, `zuul`

Experiments on different projects can be run in parallel (with sufficient memory). However, multiple experiments on the same project cannot be run simultaneously.  

The logs of each run are stored in a new folder in `evaluation_data/logs` with the name of the project and experiment mode.
To calculate the aggregated evaluation results from these log files run the individual scripts in `evaluation_scripts` described in section 3., with adapted input and output paths.

If you want to run all experiments on all projects with all three modes and both patch-level and aggregate-level analysis, you can run the following script:  

```bash
python3 run_nullrepair_and_baselines.py
```

This is long-running and expensive (~4 days and 175 USD). We recommend running the experiments in smaller batches, which also allows for parallelization.

## 5. Run on Your Own Project

You can run NullRepair on new Java projects.  
The following instructions assume that the target project uses Gradle.  
The setup of a new project is illustrated with the example project https://github.com/cbeust/jcommander/tree/3-lts with Java 17.  

1. Create a fork of the project if you do not have write access.  
We have created a fork for the example project here: https://github.com/Pascal-Joos/jcommander.

2. Clone the target project to the benchmarks directory and checkout the branch you want to run on.  

    ```bash
    cd benchmarks
    git clone git@github.com:Pascal-Joos/jcommander.git 
    cd jcommander 
    git checkout 3-lts
    ```

3. Create a new branch from this branch named `nimak/auto-code-fix`.

    ```bash
    git checkout -b nimak/auto-code-fix
    ```

4. Update the `build.gradle` or `build.gradle.kts` file to include the NullAway dependency and annotation processing. Commit these changes.  
See the following commit for an example on how to do this:  
https://github.com/Pascal-Joos/jcommander/commit/78717914991cdc053e957c58e87800507e1600ed  
This includes adding a `prepare.sh` file and, since the project is checked out under `benchmarks/`, a `settings.gradle.kts` so it builds standalone. It uses the current dependency set: stock NullAway 0.14.2 (errors.xml serialization v4), `annotator-scanner`/`annotation-util` 1.3.20-SNAPSHOT, Error Prone 2.42.0, and the `-XDshould-stop.ifError=FLOW` javac flag required by Error Prone >= 2.36.

5. Run the `prepare.sh` script to prepare the project for NullRepair.

6. Run the gradlew spotlessApply command and commit the changes.

    ```bash
    ./gradlew spotlessApply
    ```

7. Add the project to the list of target projects with adequate configuration in [annotator-core/src/main/java/edu/ucr/cs/riple/core/Main.java](annotator-core/src/main/java/edu/ucr/cs/riple/core/Main.java#L139).  
For our example, after line 139 add the following:  

    ```java
    benchmarks.put("jcommander", new Benchmark("com.beust.jcommander", "jcommander", "compileJava", "test"));
    ```

8. Rebuild the annotator-core module to include the new project in the configuration. Run this command from the root of the repository:

    ```bash
    ./gradlew spotlessApply
    ./gradlew build -x test
    ```

9. First run NullAwayAnnotator on the project to add nullability annotations to the code, without running NullRepair. Then, commit these changes to the `nimak/auto-code-fix` branch. This way, the changes made by NullRepair are more clear and the project is in a clean state before running NullRepair.  

    ```bash
    java -jar annotator-core/build/libs/annotator-core-1.3.20-SNAPSHOT.jar jcommander --mode disabled
    ```

10. Finally, run NullRepair on the project:  

    ```bash
    java -jar annotator-core/build/libs/annotator-core-1.3.20-SNAPSHOT.jar jcommander --mode advanced
    ```

    If you want changes that NullRepair applies to be pushed to the remote repository directly, add the `--pushCommits` flag.

## 6. Customize NullRepair

Key parameters are set in source files and require rebuilding after a change (step 8 of section 5).

**LLM model** — edit `modelName` in [Config.java](annotator-core/src/main/java/edu/ucr/cs/riple/core/Config.java#204):

If the model pricing is not listed in the MODEL_PRICING map at [ChatGPT.java](annotator-core/src/main/java/edu/ucr/cs/riple/core/checkers/nullaway/codefix/ChatGPT.java#97) yet, add the pricing information to the map.
Any OpenAI-compatible model name can be used.

**Per-error cost budget** — edit `COST_LIMIT` in [ChatGPT.java](annotator-core/src/main/java/edu/ucr/cs/riple/core/checkers/nullaway/codefix/ChatGPT.java#178):

NullRepair aborts LLM calls for an error once this limit is reached.

To modify the cost limit and cycle limit for the mini-SWE-agent baseline, edit `agentCostLimit` and `agentCycleLimit` in [Config.java](annotator-core/src/main/java/edu/ucr/cs/riple/core/Config.java#205):

**Analysis depth** — controls how many levels of the call graph are explored when building context. Pass `--depth <n>` on the command line (default: 6):

```bash
java -jar annotator-core/build/libs/annotator-core-1.3.20-SNAPSHOT.jar eureka --mode advanced --depth 3
```

## 7. Implementation

NullRepair extends NullAwayAnnotator. The main entry point is [annotator-core/src/main/java/edu/ucr/cs/riple/core/Main.java](annotator-core/src/main/java/edu/ucr/cs/riple/core/Main.java). The three repair modes are implemented in [annotator-core/src/main/java/edu/ucr/cs/riple/core/checkers/nullaway/codefix/](annotator-core/src/main/java/edu/ucr/cs/riple/core/checkers/nullaway/codefix/):

| Class | Mode |
| --- | --- |
| `AdvancedNullAwayCodeFix` | `advanced` (NullRepair) |
| `BasicNullAwayCodeFix` | `basic` (SinglePrompt baseline) |
| `AgentBaselineNullAwayCodeFix` | `agent_baseline` (mini-SWE-agent baseline) |

LLM communication is handled by [ChatGPT.java](annotator-core/src/main/java/edu/ucr/cs/riple/core/checkers/nullaway/codefix/ChatGPT.java) in the same package. Configuration is managed by [Config.java](annotator-core/src/main/java/edu/ucr/cs/riple/core/Config.java).
