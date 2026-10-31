import assert from 'node:assert/strict';
import { readFile } from 'node:fs/promises';
import { resolve } from 'node:path';
import { pathToFileURL } from 'node:url';

const model = await import(pathToFileURL(resolve(process.argv[2] ?? '../majung-web/model.js')));
const fixture = JSON.parse(await readFile(process.argv[3] ?? 'build/contracts/eta-api-responses.json', 'utf8'));
assert.equal(fixture.syntheticData, true);
assert.equal(fixture.cases.length, 5);
for (const scenario of fixture.cases) {
  // 250ms request RTT plus 750ms since receipt: exactly one second more than server age.
  const started = 1000, received = 1250, now = 2000;
  const timing = model.vehicleTiming(scenario.vehicle, started, received);
  const deadline = model.vehicleDeadline(scenario.vehicle, started, received);
  const view = model.vehicleView(scenario.vehicle, timing, deadline, now, true, 'ACTIVE');
  assert.equal(view?.kind, scenario.expectedKind, scenario.name);
  if (scenario.expectedSeconds != null) assert.equal(view.remainingSeconds, scenario.expectedSeconds, scenario.name);
  assert.equal(model.vehicleView(scenario.vehicle, timing, deadline, now, false, 'ACTIVE'), null);
  for (const status of ['COMPLETED', 'CANCELLED', 'EXPIRED']) {
    assert.equal(model.vehicleView(scenario.vehicle, timing, deadline, now, true, status), null);
  }
}
console.log('ETA HTTP → web compatibility: 5 scenarios passed; offline and ended states hidden');
