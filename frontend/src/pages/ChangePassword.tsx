import { api, call } from '../api/client';
import { SimpleForm } from '../components/ui';

export function ChangePassword() {
  return (
    <section>
      <h2>Change password</h2>
      <SimpleForm
        submitLabel="Change password"
        fields={[
          { name: 'currentPassword', label: 'Current password', type: 'password', required: true },
          { name: 'newPassword', label: 'New password', type: 'password', required: true },
        ]}
        onSubmit={(v) =>
          call(api.POST('/api/v1/auth/change-password', {
            body: { currentPassword: v.currentPassword ?? '', newPassword: v.newPassword ?? '' },
          }))
        }
      />
    </section>
  );
}
