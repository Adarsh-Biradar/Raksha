import {randomBytes} from 'node:crypto';
import {writeFileSync,existsSync} from 'node:fs';
import {fileURLToPath} from 'node:url';
const env=fileURLToPath(new URL('../.env',import.meta.url));
if(existsSync(env)){console.log('.env already exists; preserved existing credentials.');}
else {writeFileSync(env,'DATABASE_PASSWORD='+randomBytes(24).toString('hex')+'\nDEMO_PASSWORD='+randomBytes(18).toString('hex')+'\nAPP_PORT=8088\nBIND_ADDRESS=127.0.0.1\nCOOKIE_SECURE=false\nWORKER_ENABLED=true\n',{flag:'wx',mode:0o600});console.log('Created .env with random credentials. Use its DEMO_PASSWORD to sign in.');}
