import glob
import subprocess


BENCHMARKS = [
              "eureka", 
              "litiengine", 
              "mockito",
              "gson", 
              "jadx", 
              "libgdx", 
              "glide", 
              "conductor", 
              "retrofit", 
              "spring-boot", 
              "wala-util", 
              "zuul"
              ]
def _find_annotator_jar():
    # The shadow (fat) jar has no classifier; the plain jar is "-nonshadow". Match the version
    # from gradle.properties without hardcoding it so version bumps don't break this script.
    candidates = [
        j for j in glob.glob("./annotator-core/build/libs/annotator-core-*.jar")
        if not any(c in j for c in ("nonshadow", "sources", "javadoc"))
    ]
    if not candidates:
        raise FileNotFoundError(
            "annotator-core jar not found — run: ./gradlew build -x test")
    return candidates[0]


ANNOTATOR_JAR = _find_annotator_jar()

def prepare(benchmark):
    return

def run_annotator(benchmark):
    prepare(benchmark)
    commands = []
    commands += ["java", "-jar", ANNOTATOR_JAR]
    commands += [benchmark]
    commands += ["advanced"]
    print(commands)
    subprocess.call(commands)

    prepare(benchmark)
    commands = []
    commands += ["java", "-jar", ANNOTATOR_JAR]
    commands += [benchmark]
    commands += ["basic"]
    print(commands)
    subprocess.call(commands)

    prepare(benchmark)
    commands = []
    commands += ["java", "-jar", ANNOTATOR_JAR]
    commands += [benchmark]
    commands += ["agent_baseline"]
    print(commands)
    subprocess.call(commands)


    prepare(benchmark)
    commands = []
    commands += ["java", "-jar", ANNOTATOR_JAR]
    commands += [benchmark]
    commands += ["advanced"]
    commands += ["combined"]
    print(commands)
    subprocess.call(commands)

    prepare(benchmark)
    commands = []
    commands += ["java", "-jar", ANNOTATOR_JAR]
    commands += [benchmark]
    commands += ["basic"]
    commands += ["combined"]
    print(commands)
    subprocess.call(commands)

    prepare(benchmark)
    commands = []
    commands += ["java", "-jar", ANNOTATOR_JAR]
    commands += [benchmark]
    commands += ["agent_baseline"]
    commands += ["combined"]
    print(commands)
    subprocess.call(commands)
    
    

for benchmark in BENCHMARKS:
    # pkill -f '.*GradleDaemon.*'
    print(f"Running NullRepair for {benchmark}...")
    subprocess.run(["pkill", "-f", "GradleDaemon"])
    run_annotator(benchmark)
    print(f"Finished running NullRepair for {benchmark}.")
print("All benchmarks processed.")