import { useState } from "react";

import "./page.css";

export function CounterPage() {
  const [count, setCount] = useState(0);

  return (
    <main className="boilerplate-page">
      <section className="boilerplate-page__card">
        <p className="boilerplate-page__eyebrow">Ignition SDK Boilerplate</p>
        <h1>Standalone React Page</h1>
        <p className="boilerplate-page__description">
          This page can be mounted by the Gateway or wrapped as a Perspective component.
        </p>

        <div className="boilerplate-page__counter" aria-live="polite">
          {count}
        </div>

        <div className="boilerplate-page__actions">
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
