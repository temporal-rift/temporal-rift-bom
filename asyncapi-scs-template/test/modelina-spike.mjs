import { writeFile } from 'node:fs/promises';
import { JavaGenerator } from '@asyncapi/modelina';
import { pathToFileURL } from 'node:url';

const document = process.argv[2]
  ? pathToFileURL(process.argv[2])
  : new URL('./fixtures/multiple-operations-one-channel.yaml', import.meta.url);
const generator = new JavaGenerator({ modelType: 'record' });
const models = await generator.generate(document);

await writeFile(
  new URL('../.generated/modelina-spike.txt', import.meta.url),
  models.map((model) => model.result).join('\n'),
);
