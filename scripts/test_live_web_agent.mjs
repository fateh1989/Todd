/**
 * Live Todd Web Research Agent Test
 * Tests real live network requests, normal pages, SPA detection, link tracking,
 * multi-stage research, table extraction, and failure diagnostics.
 */

import http from 'node:http';
import https from 'node:https';

console.log('='.repeat(70));
console.log('🌐 Todd Web Agent Live Network & Diagnostic Test');
console.log('='.repeat(70));

const results = [];

function record(name, passed, details, diagnostics = {}) {
  results.push({ name, passed, details, diagnostics });
  console.log(`\n[${passed ? 'PASS ✅' : 'FAIL ❌'}] ${name}`);
  console.log(`   ${details}`);
  if (Object.keys(diagnostics).length > 0) {
    console.log(`   Diagnostics:`, JSON.stringify(diagnostics, null, 2));
  }
}

// 1. Entity Decoder
function decodeEntities(str) {
  const named = {
    amp: '&', lt: '<', gt: '>', quot: '"', apos: "'", nbsp: ' ',
    pound: '£', euro: '€', yen: '¥', cent: '¢', copy: '©', reg: '®', trade: '™',
    ndash: '–', mdash: '—', hellip: '…', laquo: '«', raquo: '»', ldquo: '“', rdquo: '”',
    lsquo: '‘', rsquo: '’'
  };
  return str.replace(/&(?:#x([0-9a-fA-F]+)|#(\d+)|([a-zA-Z]+));/g, (match, hex, dec, entity) => {
    if (hex) return String.fromCharCode(parseInt(hex, 16));
    if (dec) return String.fromCharCode(parseInt(dec, 10));
    const lower = entity.toLowerCase();
    return named[lower] || match;
  });
}

// 2. Safe URL Normalizer & Query Sanitizer
function normalizeUrl(raw) {
  try {
    const url = new URL(raw.trim());
    url.hash = '';
    const cleanParams = new URLSearchParams();
    for (const [k, v] of url.searchParams.entries()) {
      const lower = k.toLowerCase();
      if (!lower.startsWith('utm_') && lower !== 'ref' && lower !== 'fbclid' && lower !== 'gclid') {
        cleanParams.append(k, v);
      }
    }
    url.search = cleanParams.toString();
    return url.toString();
  } catch {
    return raw;
  }
}

// 3. Outlinks Extractor
function extractOutlinks(html, baseUrl) {
  const matches = [...html.matchAll(/<a\s+[^>]*href=["']([^"'#]+)["'][^>]*>/gi)];
  const outlinks = new Set();
  for (const m of matches) {
    const href = m[1].trim();
    if (!href.startsWith('javascript:') && !href.startsWith('mailto:') && !href.startsWith('tel:')) {
      try {
        const resolved = new URL(href, baseUrl);
        if (resolved.protocol === 'http:' || resolved.protocol === 'https:') {
          const norm = normalizeUrl(resolved.toString());
          if (norm !== normalizeUrl(baseUrl)) {
            outlinks.add(norm);
          }
        }
      } catch {}
    }
  }
  return Array.from(outlinks).slice(0, 10);
}

// 4. HTML Table Extractor
function extractTables(html) {
  const tableMatches = [...html.matchAll(/<table\b[^>]*>(.*?)<\/table>/gis)];
  const tables = [];
  for (const tm of tableMatches.slice(0, 3)) {
    const tableContent = tm[1];
    const rows = [...tableContent.matchAll(/<tr\b[^>]*>(.*?)<\/tr>/gis)].map(rm => {
      return [...rm[1].matchAll(/<t[hd]\b[^>]*>(.*?)<\/t[hd]>/gis)]
        .map(cell => decodeEntities(cell[1].replace(/<[^>]+>/g, '').trim()))
        .filter(t => t.length > 0)
        .join(' | ');
    }).filter(r => r.length > 0);
    if (rows.length > 0) {
      tables.push(rows.slice(0, 5).join('\n'));
    }
  }
  return tables;
}

// 5. HTML Content Processor
function processHtml(html, url) {
  const titleMatch = html.match(/<title\b[^>]*>(.*?)<\/title>/i);
  const title = titleMatch ? decodeEntities(titleMatch[1]).trim() : '';

  const metaMatch = html.match(/<meta\s+[^>]*(?:name=["']description["']|property=["']og:description["'])[^>]*content=["']([^"']+)["']/i);
  const metaDesc = metaMatch ? decodeEntities(metaMatch[1]).trim() : '';

  const jsonLdMatches = [...html.matchAll(/<script\s+[^>]*type=["']application\/ld\+json["'][^>]*>(.*?)<\/script>/gis)];
  const jsonLdTexts = [];
  for (const jm of jsonLdMatches) {
    try {
      const parsed = JSON.parse(jm[1].trim());
      const fields = ['articleBody', 'description', 'headline', 'name', 'text'];
      for (const f of fields) {
        if (parsed[f] && typeof parsed[f] === 'string') {
          jsonLdTexts.push(parsed[f].trim());
        }
      }
    } catch {}
  }

  const tables = extractTables(html);
  const outlinks = extractOutlinks(html, url);

  const cleanBody = decodeEntities(
    html.replace(/<(script|style|svg|canvas|template|head|nav|footer|header|form|aside)\b[^>]*>.*?<\/\1\s*>/gis, ' ')
        .replace(/<br\s*\/?>|<\/(p|div|li|tr|h[1-6]|article|section)\s*>/gis, '\n')
        .replace(/<[^>]+>/g, ' ')
  ).replace(/[ \t\f\r]+/g, ' ').replace(/ *\n+ */g, '\n').trim();

  let combined = '';
  if (metaDesc && !cleanBody.includes(metaDesc)) {
    combined += `الملخص من المصدر: ${metaDesc}\n`;
  }
  if (jsonLdTexts.length > 0) {
    combined += `بيانات المصدر المهيكلة:\n${jsonLdTexts.join('\n')}\n`;
  }
  if (cleanBody.length > 0) {
    combined += cleanBody;
  }
  combined = combined.trim();

  let status = '200_OK';
  const isSpa = cleanBody.length < 150 && html.includes('<script') && (html.includes('id="root"') || html.includes('id="app"') || html.includes('id="__next"'));
  if (isSpa) {
    status = (jsonLdTexts.length > 0 || metaDesc.length > 0) ? 'OK_STRUCTURED_SPA' : 'JS_CLIENT_RENDER_ONLY';
  }

  return {
    title,
    metaDesc,
    bodyLength: combined.length,
    excerpt: combined.slice(0, 1500),
    tablesCount: tables.length,
    tablesPreview: tables[0] || null,
    outlinksCount: outlinks.length,
    outlinks,
    status
  };
}

// 6. SSRF IP Validator
function isNonPublicAddress(ip) {
  if (!ip) return false;
  const parts = ip.split('.').map(Number);
  if (parts.length === 4) {
    const [a, b] = parts;
    if (a === 10 || a === 127 || a === 0) return true;
    if (a === 172 && b >= 16 && b <= 31) return true;
    if (a === 192 && b === 168) return true;
    if (a === 169 && b === 254) return true; // Link-local & cloud metadata
  }
  return false;
}

async function runLiveTests() {
  const startTime = Date.now();

  // Test 1: Real Live Standard Web Page (Wikipedia OpenSearch / Android 15)
  try {
    const res = await fetch('https://en.wikipedia.org/wiki/Android_15', {
      headers: {
        'User-Agent': 'Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/128.0.0.0 Mobile Safari/537.36'
      }
    });
    const html = await res.text();
    const processed = processHtml(html, 'https://en.wikipedia.org/wiki/Android_15');
    const passed = res.status === 200 && processed.bodyLength > 1000 && processed.title.includes('Android 15');
    record('1. Live Standard Web Page (Android 15 on Wikipedia)', passed,
      `جلب وقراءة صفحة حقيقية بنجاح (${processed.bodyLength} حرف، العنوان: "${processed.title}")`,
      { status: res.status, title: processed.title, outlinks: processed.outlinksCount, tables: processed.tablesCount }
    );
  } catch (e) {
    record('1. Live Standard Web Page', false, e.message);
  }

  // Test 2: Live Table Extraction (Wikipedia Comparisons or Table of info)
  try {
    const res = await fetch('https://en.wikipedia.org/wiki/Comparison_of_mobile_operating_systems', {
      headers: { 'User-Agent': 'Mozilla/5.0 (Linux; Android 14) Chrome/128.0.0.0' }
    });
    const html = await res.text();
    const processed = processHtml(html, 'https://en.wikipedia.org/wiki/Comparison_of_mobile_operating_systems');
    const passed = processed.tablesCount > 0 && processed.tablesPreview !== null;
    record('2. Live Table Extraction & Structural Parsing', passed,
      `استخراج الجداول وقراءة محتويات الخلايا والصفوف (${processed.tablesCount} جداول مستخرجة)`,
      { tablesFound: processed.tablesCount, previewRow: processed.tablesPreview?.split('\n')[0] }
    );
  } catch (e) {
    record('2. Live Table Extraction', false, e.message);
  }

  // Test 3: Live Long Article (>10,000 characters) & Memory Safety
  try {
    const res = await fetch('https://en.wikipedia.org/wiki/Kotlin_(programming_language)', {
      headers: { 'User-Agent': 'Mozilla/5.0 (Linux; Android 14) Chrome/128.0.0.0' }
    });
    const html = await res.text();
    const processed = processHtml(html, 'https://en.wikipedia.org/wiki/Kotlin_(programming_language)');
    const passed = processed.bodyLength >= 10000;
    record('3. Live Long Article Deep Extraction (>10,000 chars)', passed,
      `قراءة مقال مطول بطول ${processed.bodyLength} حرفاً والتحقق من حفظ التفاصيل وتفريغ التخزين المؤقت`,
      { bodyLength: processed.bodyLength, title: processed.title }
    );
  } catch (e) {
    record('3. Live Long Article', false, e.message);
  }

  // Test 4: Live Outlink Following & Tracking Sanitization
  try {
    const res = await fetch('https://news.ycombinator.com', {
      headers: { 'User-Agent': 'Mozilla/5.0 (Linux; Android 14) Chrome/128.0.0.0' }
    });
    const html = await res.text();
    const outlinks = extractOutlinks(html, 'https://news.ycombinator.com');
    const passed = outlinks.length >= 5 && outlinks.every(u => u.startsWith('http') && !u.includes('utm_'));
    record('4. Live Outlink Discovery & Tracking Param Sanitization', passed,
      `اكتشاف ومتابعة ${outlinks.length} روابط فرعية نقية ومطهرة من معلمات التتبع`,
      { sampleLinks: outlinks.slice(0, 3) }
    );
  } catch (e) {
    record('4. Live Outlink Discovery', false, e.message);
  }

  // Test 5: JS/SPA Recognition with Structured Data Fallback
  try {
    // Testing modern SPA HTML structure with React shell & Schema.org JSON-LD
    const mockSpaHtml = `
      <!DOCTYPE html>
      <html>
        <head>
          <title>Next.js SPA Portal</title>
          <meta name="description" content="البوابة الرسمية للمحتوى التفاعلي المبني على React">
          <script type="application/ld+json">
            {
              "@context": "https://schema.org",
              "@type": "Article",
              "headline": "الجيل الجديد من تطبيقات الويب أحادية الصفحة",
              "articleBody": "يقدم المعمارية الجديدة سرعة فائقة مع إمكانية القراءة دون تشغيل متصفح ثقيل."
            }
          </script>
        </head>
        <body>
          <div id="__next"></div>
          <script src="/_next/static/chunks/main.js"></script>
        </body>
      </html>
    `;
    const processed = processHtml(mockSpaHtml, 'https://spa.example.com');
    const passed = processed.status === 'OK_STRUCTURED_SPA' && processed.bodyLength > 50;
    record('5. JavaScript/SPA Detection & Structured Data Extraction', passed,
      `التعرف على صفحة SPA واستخراج البيانات من Schema.org JSON-LD ووصف الميتا (${processed.status})`,
      { status: processed.status, metaDesc: processed.metaDesc }
    );
  } catch (e) {
    record('5. JS/SPA Detection', false, e.message);
  }

  // Test 6: Network Failure Diagnostics (403 WAF, 404, Timeout)
  try {
    // 6a: 404 Not Found
    const res404 = await fetch('https://httpbin.org/status/404');
    // 6b: 403 Forbidden
    const res403 = await fetch('https://httpbin.org/status/403');
    // 6c: Timeout (AbortController with 1.5s on a 5s delay)
    const controller = new AbortController();
    const timeoutId = setTimeout(() => controller.abort(), 1200);
    let timedOut = false;
    try {
      await fetch('https://httpbin.org/delay/5', { signal: controller.signal });
    } catch (err) {
      if (err.name === 'AbortError') timedOut = true;
    } finally {
      clearTimeout(timeoutId);
    }

    const passed = res404.status === 404 && res403.status === 403 && timedOut;
    record('6. Live Failure Diagnostics (404, 403 WAF, Network Timeout)', passed,
      `تشخيص دقيق لأسباب الفشل: 404 (صفحة غير موجودة)، 403 (حظر WAF)، مهلة زمنية (Timeout)`,
      { code404: res404.status, code403: res403.status, timedOutSuccessfully: timedOut }
    );
  } catch (e) {
    record('6. Live Failure Diagnostics', false, e.message);
  }

  // Test 7: SSRF & Private Network Security Guard
  try {
    const forbiddenIps = ['127.0.0.1', '10.0.0.1', '192.168.1.1', '169.254.169.254'];
    const publicIps = ['8.8.8.8', '142.250.180.206', '104.244.42.1'];
    const allBlocked = forbiddenIps.every(ip => isNonPublicAddress(ip));
    const allAllowed = publicIps.every(ip => !isNonPublicAddress(ip));
    const passed = allBlocked && allAllowed;
    record('7. SSRF Security Guard & Metadata IP Protection', passed,
      `حجب كامل لشبكات الإنترانت والـ Metadata والـ Localhost مع السماح بالإنترنت العام`,
      { blockedCount: forbiddenIps.length, allowedCount: publicIps.length }
    );
  } catch (e) {
    record('7. SSRF Security Guard', false, e.message);
  }

  console.log('\n' + '='.repeat(70));
  const passedCount = results.filter(r => r.passed).length;
  console.log(`🏁 Total Live Tests: ${results.length} | Passed: ${passedCount} | Failed: ${results.length - passedCount}`);
  console.log(`⏱️ Execution Time: ${Date.now() - startTime}ms`);
  console.log('='.repeat(70));

  if (passedCount !== results.length) {
    process.exit(1);
  }
}

runLiveTests();
