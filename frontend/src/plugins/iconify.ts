import { addCollection } from '@iconify/vue/offline';
import { offlineIconCollections } from '@/assets/generated/iconify-offline';

/** Setup the iconify offline */
export function setupIconifyOffline() {
  offlineIconCollections.forEach(collection => addCollection(collection));
}
