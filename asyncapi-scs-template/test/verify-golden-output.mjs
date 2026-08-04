import { readFile } from 'node:fs/promises';
import { fileURLToPath } from 'node:url';
import Generator from '@asyncapi/generator';

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
