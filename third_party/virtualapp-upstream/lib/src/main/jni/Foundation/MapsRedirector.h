#ifndef MAPS_REDIRECTOR_H
#define MAPS_REDIRECTOR_H

int redirect_proc_file(const char *const pathname, const int flags, const int mode);
int redirect_proc_maps(const char *const pathname, const int flags, const int mode);

#endif // MAPS_REDIRECTOR_H
