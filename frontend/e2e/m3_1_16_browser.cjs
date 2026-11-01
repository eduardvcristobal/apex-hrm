/* M3.1.16: actual Chromium navigation against Vite+Spring Boot+PostgreSQL.
 * CI-only synthetic bearer token; no live bank or financial posting action.
 */
const { chromium } = require('playwright');
const assert = require('node:assert/strict');
const token = 'a'.repeat(64);
(async () => {
 const browser = await chromium.launch({headless:true});
 try {
  for(const viewport of [{width:1440,height:900},{width:390,height:844}]){
   const page = await browser.newPage({viewport});
   await page.addInitScript(({token}) => {
    sessionStorage.setItem('apexHrm.accessToken',token);
    sessionStorage.setItem('apexHrm.expiresAt',String(Date.now()+3600000));
   },{token});
   let consoleErrors=[];
   page.on('pageerror',e=>consoleErrors.push(e.message));
   await page.goto('http://127.0.0.1:5173/dashboard/people',{waitUntil:'domcontentloaded'});
   await page.getByRole('heading',{name:'People',exact:true}).waitFor({timeout:30000});
   await page.getByText('Jamie Rivera',{exact:true}).first().waitFor({timeout:30000});
   // Prefer the profile action as it exercises the real Personnel navigation and backend.
   const open=page.getByRole('button',{name:/Open profile/}).first();
   await open.click();
   await page.getByRole('button',{name:'HR lifecycle'}).waitFor({timeout:15000});
   await page.getByRole('button',{name:'HR lifecycle'}).click();
   await page.getByRole('heading',{name:/Onboarding.*separation cases/}).waitFor({timeout:15000});
   await page.getByRole('heading',{name:/Source-ledger reconciliation/}).waitFor({timeout:15000});
   const section=page.getByRole('region',{name:'Final-pay source-ledger review'});
   assert.match(await section.innerText(),/REVIEW REQUIRED|REVIEW_REQUIRED|Awaiting source-ledger review/);
   assert.match(await page.locator('body').innerText(),/Disbursement enabled: No/);
   if(consoleErrors.length)throw new Error('Browser errors: '+consoleErrors.join('; '));
   console.log('PASS: Chromium '+viewport.width+'px authenticated Personnel > HR lifecycle > final-pay source ledger, payment stays disabled');
   await page.close();
  }
  // Synthetic unauthenticated user must not be able to render the secure workspace.
  const guest=await browser.newPage();
  await guest.goto('http://127.0.0.1:5173/dashboard/people',{waitUntil:'domcontentloaded'});
  await guest.getByRole('heading',{name:'People',exact:true}).waitFor({state:'hidden',timeout:15000});
  assert.equal(await guest.getByRole('heading',{name:/Final-pay source-ledger review/}).count(),0);
  console.log('PASS: guest cannot view financial Personnel details');
  await guest.close();
 } finally {await browser.close();}
})().catch(e=>{console.error(e);process.exitCode=1;});
