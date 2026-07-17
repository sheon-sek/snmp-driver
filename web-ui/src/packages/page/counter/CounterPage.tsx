import { useState } from "react";

import "./page.css";

export function CounterPage() {
  const [count, setCount] = useState(0);

  return (
    <main className="counter-page">
      <section className="counter-page__card">
        <p className="counter-page__eyebrow">Gateway Module Example</p>
        <h1>Standalone React Page</h1>
        <p className="counter-page__description">
          This page can be mounted by the Gateway or wrapped as a Perspective component.
        </p>

        <div className="counter-page__counter" aria-live="polite">
          {count}
        </div>

        <div className="counter-page__actions">
          <button type="button" onClick={() => setCount((value) => value - 1)}>
            Decrease
          </button>
          <button type="button" onClick={() => setCount(0)}>
            Reset
          </button>
          <button type="button" onClick={() => setCount((value) => value + 1)}>
            Increase
          </button>
        </div>
      </section>
    </main>
  );
}

export default CounterPage;
