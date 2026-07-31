export default class WorkPool<W, T, R> {
    #pool = new Map<W, boolean>();
    #action: (worker: W, input: T) => Promise<R>;
    #inputQueue: T[] = [];
    #outputQueue: Promise<R>[] = [];
    #waiters!: Promise<Promise<R>[]>;
    #waitersResolver!: ((output: Promise<R>[]) => void);

    constructor(workers: W[], action: (worker: W, input: T) => Promise<R>) {
        workers.forEach(worker => this.#pool.set(worker, false));
        this.#action = action;
        this.#resetWaiters();
    }

    #resetWaiters() {
        //ES2024 has Promise.withResolvers
        this.#waiters = new Promise(resolve => {
            this.#waitersResolver = resolve;
        });
    }

    queue(task: T) {
        for (const [worker, busy] of this.#pool) {
            if (!busy) {
                this.#pool.set(worker, true);
                this.#startWorker(worker, task);
                return;
            }
        }

        this.#inputQueue.push(task);
    }

    completion() {
        return this.#waiters;
    }

    async #startWorker(worker: W, task: T) {
        for (let currentTask: T | undefined = task; currentTask; currentTask = this.#inputQueue.shift()) {
            const result = this.#action(worker, currentTask);
            this.#outputQueue.push(result);
            try {
                await result;
            } catch (error) {
                console.error(worker, 'crashed', error);
            }
        }

        this.#pool.set(worker, false);
        for (const busy of this.#pool.values()) {
            if (busy) return;
        }

        this.#waitersResolver(this.#outputQueue);
        this.#outputQueue = [];
        this.#resetWaiters();
    }
}