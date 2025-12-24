// Test luxon toRelative() behavior
import { DateTime } from 'luxon';

// Simulate a timestamp from 42 seconds ago
const now = DateTime.now();
const fortyTwoSecondsAgo = now.minus({ seconds: 42 });

console.log('Current time:', now.toISO());
console.log('42 seconds ago:', fortyTwoSecondsAgo.toISO());
console.log('\n--- Default toRelative() ---');
console.log('Result:', fortyTwoSecondsAgo.toRelative());

console.log('\n--- toRelative with unit option ---');
console.log('Result (seconds):', fortyTwoSecondsAgo.toRelative({ unit: 'seconds' }));

console.log('\n--- toRelative with style narrow ---');
console.log('Result:', fortyTwoSecondsAgo.toRelative({ style: 'narrow' }));

console.log('\n--- Testing at 55 seconds late in minute ---');
const specificTime = DateTime.fromISO('2024-01-15T10:30:55.000Z');
const specificTimeOld = DateTime.fromISO('2024-01-15T10:30:13.000Z'); // 42 seconds earlier
console.log('Specific time (late in minute):', specificTime.toISO());
console.log('42 seconds earlier:', specificTimeOld.toISO());
console.log('Default toRelative():', specificTimeOld.toRelative({ base: specificTime }));
console.log('With unit seconds:', specificTimeOld.toRelative({ base: specificTime, unit: 'seconds' }));
