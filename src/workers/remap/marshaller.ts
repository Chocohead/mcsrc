class WorkPool<W, T, R> {
    #pool = new Map<W, boolean>();
    #action: (worker: W, input: T) => Promise<R>;
    #inputQueue: T[] = [];
    #outputQueue: R[] = [];
    #waiters: Promise<R[]> | null = null;
    #waitersResolver: ((output: R[]) => void) | null = null;

    constructor(workers: W[], action: (worker: W, input: T) => Promise<R>) {
        workers.forEach(worker => this.#pool.set(worker, false));
        this.#action = action;
    }

    queue(task: T) {
        for (const [worker, busy] of this.#pool) {
            if (!busy) {
                this.#pool.set(worker, true);
                return this.startWorker(worker, task);
            }
        }

        this.#inputQueue.push(task);
    }

    completion() {
        for (const busy of this.#pool.values()) {
            if (busy) {
                if (!this.#waiters) {
                    //ES2024 has Promise.withResolvers
                    this.#waiters = new Promise(resolve => {
                        this.#waitersResolver = resolve;
                    });
                }

                return this.#waiters;
            }
        }

        return Promise.resolve();
    }

    private async startWorker(worker: W, task: T) {
        try {
            this.#outputQueue.push(await this.#action(worker, task));
        } catch (error) {
            //this.#outputQueue.push(error);
            console.error(worker, 'crashed', error);
        } finally {
            const nextTask = this.#inputQueue.shift();
            if (nextTask) {
                this.startWorker(worker, nextTask);
            } else {
                this.#pool.set(worker, false);
                for (const busy of this.#pool.values()) {
                    if (busy) return;
                }

                if (this.#waitersResolver) {
                    this.#waitersResolver(this.#outputQueue);
                    this.#waitersResolver = null;
                    this.#waiters = null;
                }
                this.#outputQueue.length = 0;
            }
        }
    }
}