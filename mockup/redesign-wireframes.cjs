const fs = require('node:fs');
const path = require('node:path');
const output = path.join(__dirname, 'dist', 'wireframes');
for (const [group, first, last] of [['phone', 1, 13], ['car', 14, 18]]) {
  let template = fs.readFileSync(path.join(__dirname, 'templates', group + '.html'), 'utf8');
  if (group === 'car') {
    const behavior = fs.readFileSync(path.join(__dirname, 'templates', 'car-behavior.js'), 'utf8');
    template = template.replace(/<script>[\s\S]*?<\/script>/, () => '<script>\n' + behavior + '\n</script>');
  }
  for (let id = first; id <= last; id++) {
    fs.writeFileSync(path.join(output, String(id).padStart(2, '0') + '.html'), template.replace('__SCREEN__', String(id)));
  }
}
console.log('Updated 18 screens. Vehicle navigation is a persistent sibling of the Chagok panel.');
