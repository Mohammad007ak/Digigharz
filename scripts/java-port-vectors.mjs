// Reference values from the JS implementation, for the Java backend's tests:
//   node scripts/java-port-vectors.mjs
import { writeFileSync } from "node:fs";
import { toJalali, addJalaliMonths } from "../src/lib/jalali.js";
import { buildChain, nonceDigest, pickWinner, sha256Hex } from "../src/lib/fairness.js";
const out = new URL("../backend/src/test/resources/vectors", import.meta.url).pathname;

// One timestamp every ~7h37m from 1975 to 2105: covers both sides of Tehran midnight and old DST years.
// Line i is the Jalali date (Tehran time) of START + i * STEP; the step drifts
// across the time of day, so both sides of Tehran midnight are covered.
const START = Date.UTC(1975, 0, 1), STEP = 25 * 3600_000 + 7 * 60_000;
const jalali = [`${START} ${STEP}`];
for (let t = START; t < Date.UTC(2105, 0, 1); t += STEP) {
  const j = toJalali(new Date(t));
  jalali.push(`${j.year}-${j.month}-${j.day}`);
}
writeFileSync(`${out}/jalali.txt`, jalali.join("\n") + "\n");

let seed = 42;
const rnd = () => ((seed = (seed * 1103515245 + 12345) % 2 ** 31) / 2 ** 31);
const months = [];
for (let i = 0; i < 3000; i++) {
  const t = Date.UTC(2000, 0, 1) + Math.floor(rnd() * 90 * 365.25 * 86400000);
  const n = Math.floor(rnd() * 30);
  months.push([t, n, addJalaliMonths(t, n)]);
}
writeFileSync(`${out}/add-months.json`, JSON.stringify(months));

const fair = [];
for (let i = 0; i < 200; i++) {
  const secret = (await sha256Hex(`secret-${i}`));
  const length = 1 + (i % 24);
  const chain = await buildChain(secret, length);
  const nonces = Array.from({ length: 1 + (i % 12) }, (_, k) => `n${i}-${k}`.padEnd(32, "a"));
  const digest = await nonceDigest(nonces);
  const eligible = Array.from({ length: 1 + (i % 11) }, (_, k) => `m${(i * 7 + k * 13) % 97}x${k}`);
  const month = 2 + (i % 10);
  const reveal = chain[1 + (i % length)];
  const { seed: s, winner } = await pickWinner({ reveal, digest, month, eligible });
  fair.push({ secret, length, anchor: chain[0], reveal, nonces, digest, eligible, month, seed: s, winner });
}
writeFileSync(`${out}/fairness.json`, JSON.stringify(fair));
console.log(jalali.length, months.length, fair.length);
