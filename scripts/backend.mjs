// Runs the Java backend with the Maven wrapper (no Maven install needed):
//   npm run backend          start it (settings from .env)
//   npm run backend:test     run its tests
import { spawnSync } from "node:child_process";

const goals = process.argv[2] === "test" ? ["test"] : ["spring-boot:run"];
const windows = process.platform === "win32";
const result = spawnSync(windows ? "mvnw.cmd" : "./mvnw", ["-q", ...goals], {
  cwd: new URL("../backend", import.meta.url),
  stdio: "inherit",
  shell: windows,
});
if (result.error) console.error(result.error.message);
process.exit(result.status ?? 1);
