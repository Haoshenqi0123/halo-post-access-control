import type { ListedPost } from '@halo-dev/api-client'
import { VDropdown, VDropdownItem } from '@halo-dev/components'
import { definePlugin } from '@halo-dev/ui-shared'
import axios from 'axios'
import { markRaw } from 'vue'

const ACCESS_ANNOTATION = 'permission.haoshenqi.com/access'

type AccessPermission = 'PUBLIC' | 'NORMAL' | 'PRIVATE'

const permissionLabels: Record<AccessPermission, string> = {
  PUBLIC: '公开（任何人可见）',
  NORMAL: '普通（登录可见）',
  PRIVATE: '私有（仅自己可见）',
}

function currentPermission(post: ListedPost): AccessPermission {
  const value = post.post.metadata.annotations?.[ACCESS_ANNOTATION]
  if (value === 'PUBLIC' || value === 'NORMAL' || value === 'PRIVATE') {
    return value
  }
  return 'NORMAL'
}

async function updatePermission(post: ListedPost, permission: AccessPermission) {
  const annotations = {
    ...(post.post.metadata.annotations || {}),
    [ACCESS_ANNOTATION]: permission,
  }
  const payload = {
    ...post.post,
    metadata: {
      ...post.post.metadata,
      annotations,
    },
  }
  await axios.put(`/apis/content.halo.run/v1alpha1/posts/${post.post.metadata.name}`, payload)
  post.post.metadata.annotations = annotations
  window.alert(`访问权限已设置为：${permissionLabels[permission]}`)
}

export default definePlugin({
  components: {},
  routes: [],
  extensionPoints: {
    'post:list-item:operation:create': (post) => {
      const current = currentPermission(post.value)
      return [
        {
          priority: 30,
          component: markRaw(VDropdown),
          label: '访问权限',
          permissions: [],
          children: (['PUBLIC', 'NORMAL', 'PRIVATE'] as AccessPermission[]).map((permission) => ({
            priority: 0,
            component: markRaw(VDropdownItem),
            label: `${permissionLabels[permission]}${current === permission ? '（当前）' : ''}`,
            permissions: [],
            action: async () => {
              await updatePermission(post.value, permission)
            },
          })),
        },
      ]
    },
  },
})
