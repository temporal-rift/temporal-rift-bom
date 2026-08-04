import { fileURLToPath } from 'node:url';
import Generator from '@asyncapi/generator';

const rootDir = fileURLToPath(new URL('..', import.meta.url));
const outputDir = fileURLToPath(new URL('../.generated/collision', import.meta.url));
const fixturePath = fileURLToPath(
  new URL('./fixtures/colliding-message-names.yaml', import.meta.url),
);

const generator = new Generator(rootDir, outputDir, { forceWrite: true, install: false });

let thrown;
try {
  await generator.generateFromFile(fixturePath);
} catch (error) {
  thrown = error;
}

if (!thrown) {
  throw new Error(
    'Expected generation to reject "order-created" and "order_created" as colliding Java identifiers, but it succeeded.',
  );
}

if (!thrown.message.includes('both generate the Java identifier')) {
  throw new Error(`Generation threw, but not for the expected collision reason: ${thrown.message}`);
}
