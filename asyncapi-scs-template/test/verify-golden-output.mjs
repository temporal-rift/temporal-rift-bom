import { readFile } from 'node:fs/promises';
import { execFile } from 'node:child_process';
import { promisify } from 'node:util';
import { fileURLToPath } from 'node:url';
import Generator from '@asyncapi/generator';

const execFileAsync = promisify(execFile);

const rootDir = fileURLToPath(new URL('..', import.meta.url));
const outputDir = fileURLToPath(new URL('../.generated', import.meta.url));
const fixturePath = fileURLToPath(
  new URL('./fixtures/multiple-operations-one-channel.yaml', import.meta.url),
);

const generator = new Generator(rootDir, outputDir, { forceWrite: true, install: false });
await generator.generateFromFile(fixturePath);

const outputPath = new URL(
  '../.generated/GeneratedChannelContract.java',
  import.meta.url,
);
const output = await readFile(outputPath, 'utf8');

if (!output.includes('OUTPUT_BINDING = "game-events-out"')) {
  throw new Error('Expected one generated binding for the game.events channel.');
}

if (!output.includes('publishLobbyCreated') || !output.includes('publishGameStarted')) {
  throw new Error('Expected one typed producer method per AsyncAPI message.');
}

if (output.includes('publishLobbyCreated-out') || output.includes('publishGameStarted-out')) {
  throw new Error('Generated bindings must not derive from AsyncAPI operation identifiers.');
}

if (!output.includes('public record LobbyCreatedPayload(String gameId) {}')) {
  throw new Error('Expected LobbyCreatedPayload to declare a gameId field derived from its schema, not a raw json blob.');
}

if (!output.includes('LOBBY_CREATED_EVENT_TYPE = "LobbyCreated"') || !output.includes('GAME_STARTED_EVENT_TYPE = "GameStarted"')) {
  throw new Error('Expected an event-type constant per message.');
}

if (!output.includes('case LOBBY_CREATED_EVENT_TYPE -> onLobbyCreated(')) {
  throw new Error('Expected Consumer.dispatch to route eventType to its typed handler.');
}

const classOutputDir = fileURLToPath(new URL('../.generated/classes', import.meta.url));
await execFileAsync('javac', ['-d', classOutputDir, fileURLToPath(outputPath)]);
