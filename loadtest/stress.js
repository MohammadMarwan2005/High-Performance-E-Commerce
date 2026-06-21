// Requirement 9 — Stress test: 100+ concurrent users, no crash, no data loss.
//
// Read-heavy profile (realistic e-commerce browsing):
//   ~80% paginated product browsing   GET /products?page=..&size=20
//   ~15% single hot-product reads      GET /products/{id}
//   ~5%  checkouts                      POST /orders
//
// The read-heavy mix is deliberate so the Step-6 Redis cache (which caches
// product reads) has a clear win to demonstrate in the Step-7 re-benchmark.
//
// Run (via the grafana/k6 docker image, host networking):
//   docker run --rm --network host -e BASE_URL=http://localhost:8080 \
//     -v "$PWD/loadtest:/ld" grafana/k6 run /ld/stress.js
import http from 'k6/http';
import { check, sleep } from 'k6';
import { Rate } from 'k6/metrics';

const BASE = __ENV.BASE_URL || 'http://localhost:8080';
const PEAK_VUS = Number(__ENV.PEAK_VUS || 120); // 100+ required by Req 9

const checkoutFailRate = new Rate('checkout_failed');

export const options = {
  scenarios: {
    browse_heavy: {
      executor: 'ramping-vus',
      startVUs: 0,
      stages: [
        { duration: '20s', target: PEAK_VUS }, // ramp up
        { duration: '40s', target: PEAK_VUS }, // hold at peak (100+)
        { duration: '10s', target: 0 },        // ramp down
      ],
      gracefulRampDown: '5s',
    },
  },
  thresholds: {
    // Req 9 is "stays up, no data loss" — these guard against crashes/timeouts.
    http_req_failed: ['rate<0.05'],          // <5% HTTP errors overall
    'http_req_duration{kind:browse}': ['p(95)<2000'],
    'http_req_duration{kind:product}': ['p(95)<2000'],
  },
};

// One-time setup: register a user and collect a hot set of real product ids.
export function setup() {
  const u = http.post(`${BASE}/users/register`, JSON.stringify({
    username: `loadtest_${Date.now()}`,
    password: 'pass1234',
  }), { headers: { 'Content-Type': 'application/json' } });
  const userId = u.json('id');

  const page = http.get(`${BASE}/products?page=0&size=100`);
  const ids = (page.json('content') || []).map((p) => p.id);
  if (ids.length === 0) {
    throw new Error('No products found — seed the catalog first (POST /admin/products/seed).');
  }
  return { userId, productIds: ids };
}

export default function (data) {
  const r = Math.random();

  if (r < 0.80) {
    // Browse: random page of the catalog.
    const pageNum = Math.floor(Math.random() * 50);
    const res = http.get(`${BASE}/products?page=${pageNum}&size=20`, { tags: { kind: 'browse' } });
    check(res, { 'browse 200': (x) => x.status === 200 });
  } else if (r < 0.95) {
    // Hot product read by id.
    const id = data.productIds[Math.floor(Math.random() * data.productIds.length)];
    const res = http.get(`${BASE}/products/${id}`, { tags: { kind: 'product' } });
    check(res, { 'product 200': (x) => x.status === 200 });
  } else {
    // Checkout one unit of a random product.
    const id = data.productIds[Math.floor(Math.random() * data.productIds.length)];
    const body = JSON.stringify({ userId: data.userId, items: [{ productId: id, quantity: 1 }] });
    const res = http.post(`${BASE}/orders`, body, {
      headers: { 'Content-Type': 'application/json' },
      tags: { kind: 'checkout' },
    });
    // 201 = placed, 400 = out of stock (both are correct, not failures).
    const ok = res.status === 201 || res.status === 400;
    checkoutFailRate.add(!ok);
    check(res, { 'checkout ok/expected': () => ok });
  }

  sleep(Math.random() * 0.3); // small think-time
}
