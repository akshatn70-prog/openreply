import { spawn } from "node:child_process";

const processes = [];

function start(command, args, name) {
  const child = spawn(command, args, {
    stdio: "inherit",
    env: process.env,
    shell: false,
  });

  processes.push(child);

  child.on("exit", (code, signal) => {
    console.log(`[${name}] exited (code=${code ?? "null"}, signal=${signal ?? "none"})`);

    if (code !== 0 && signal == null) {
      shutdown(code ?? 1);
    }
  });

  child.on("error", (error) => {
    console.error(`[${name}] failed to start:`, error);
    shutdown(1);
  });

  return child;
}

let shuttingDown = false;

function shutdown(code = 0) {
  if (shuttingDown) return;
  shuttingDown = true;

  for (const child of processes) {
    if (!child.killed) {
      child.kill("SIGTERM");
    }
  }

  setTimeout(() => process.exit(code), 5_000).unref();
}

process.on("SIGINT", () => shutdown(0));
process.on("SIGTERM", () => shutdown(0));
process.on("exit", () => {
  for (const child of processes) {
    if (!child.killed) child.kill("SIGTERM");
  }
});

console.log("[OpenReply] Applying pending database migrations...");
const migration = spawn(process.platform === "win32" ? "npx.cmd" : "npx", ["prisma", "migrate", "deploy"], {
  stdio: "inherit",
  env: process.env,
  shell: false,
});

migration.on("error", (error) => {
  console.error("[OpenReply] Database migration failed to start:", error);
  process.exit(1);
});

migration.on("exit", (code, signal) => {
  if (code !== 0 || signal != null) {
    console.error(`[OpenReply] Database migrations failed (code=${code ?? "null"}, signal=${signal ?? "none"})`);
    process.exit(code ?? 1);
  }

  console.log("[OpenReply] Database migrations complete.");
  console.log("[OpenReply] Starting Next.js web server...");
  start(process.platform === "win32" ? "npm.cmd" : "npm", ["run", "start:web"], "Web");
});

console.log("[OpenReply] Starting DM worker...");
start(process.platform === "win32" ? "npm.cmd" : "npm", ["run", "worker"], "Worker");
