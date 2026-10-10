import request from '@/api/request'

export interface Host {
  id?: number
  hostKey?: string
  name: string
  address: string
  port: number
  username: string
  authType: 'PASSWORD' | 'PRIVATE_KEY'
  credentialId?: string
  privateKeyPath?: string
  description?: string
  sortOrder?: number
}

export default {
  list: () => request.get('/hosts') as Promise<Host[]>,
  get: (id: number) => request.get(`/hosts/${id}`) as Promise<Host>,
  create: (host: Host) => request.post('/hosts', host) as Promise<Host>,
  update: (id: number, host: Host) => request.put(`/hosts/${id}`, host) as Promise<Host>,
  remove: (id: number) => request.delete(`/hosts/${id}`) as Promise<void>,
  reorder: (orderedIds: number[]) => request.put('/hosts/reorder', { orderedIds }) as Promise<void>,
}
