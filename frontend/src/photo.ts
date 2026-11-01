export async function prepareEmployeePhoto(file: File): Promise<string> {
  if (!['image/png', 'image/jpeg', 'image/webp'].includes(file.type)) throw new Error('Choose a PNG, JPG, or WebP photo.');
  const bitmap = await createImageBitmap(file);
  const scale = Math.min(1, 480 / Math.max(bitmap.width, bitmap.height));
  const canvas = document.createElement('canvas');
  canvas.width = Math.max(1, Math.round(bitmap.width * scale));
  canvas.height = Math.max(1, Math.round(bitmap.height * scale));
  const context = canvas.getContext('2d');
  if (!context) throw new Error('Could not prepare this photo.');
  context.drawImage(bitmap, 0, 0, canvas.width, canvas.height);
  bitmap.close();
  let quality = 0.82;
  let blob: Blob | null = null;
  while (quality >= 0.42) {
    blob = await new Promise<Blob | null>(resolve => canvas.toBlob(resolve, 'image/jpeg', quality));
    if (blob && blob.size <= 240 * 1024) break;
    quality -= 0.1;
  }
  if (!blob || blob.size > 250 * 1024) throw new Error('This photo is too large. Choose a smaller image.');
  return await new Promise((resolve, reject) => {
    const reader = new FileReader();
    reader.onload = () => typeof reader.result === 'string' ? resolve(reader.result) : reject(new Error('Could not read the prepared photo.'));
    reader.onerror = () => reject(new Error('Could not read the prepared photo.'));
    reader.readAsDataURL(blob);
  });
}
