import { abilityAccessCtrl, bundleManager, common, Permissions } from '@kit.AbilityKit';

export async function allAllowed(perms: Permissions[]): Promise<boolean> {
  const bundleInfo = bundleManager.getBundleInfoForSelfSync(
    bundleManager.BundleFlag.GET_BUNDLE_INFO_WITH_APPLICATION);
  const atManager = abilityAccessCtrl.createAtManager();
  const tokenId = bundleInfo.appInfo.accessTokenId;
  for (const p of perms) {
    const status = atManager.checkAccessTokenSync(tokenId, p);
    if (status !== abilityAccessCtrl.GrantStatus.PERMISSION_GRANTED) {
      return false;
    }
  }
  return true;
}

export async function requestPermissions(perms: Permissions[]): Promise<boolean> {
  const atManager = abilityAccessCtrl.createAtManager();
  const ctx = getContext() as common.UIAbilityContext;
  const result = await atManager.requestPermissionsFromUser(ctx, perms);
  for (const r of result.authResults) {
    if (r !== 0) {
      return false;
    }
  }
  return true;
}
