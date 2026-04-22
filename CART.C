#include "ultra.h"

Cart  cart;

int cart_check(char *fname)
{
    FILE *f1;
    int   a;
    f1=fopen(fname,"rb");
    if(!f1) return(-1);

    fread(&a,1,4,f1);

    fseek(f1,0L,SEEK_END);
    cart.size=ftell(f1);

    fclose(f1);

    cart.fileflip=a;

    if(a==0x80371240) return(0x0123);
    if(a==0x37804012) return(0x1032);
    if(a==0x12408037) return(0x2301);
    if(a==0x40123780) return(0x3210);
    return(-2);
}

int cart_save(char *fname)
{
    FILE *f1;
    f1=fopen(fname,"wb");
    if(!f1) return(1);

    fwrite(cart.data,1,cart.size,f1);

    fclose(f1);
    return(0);
}

int cart_load(char *fname)
{
    FILE *f1;

    f1=fopen(fname,"rb");
    if(!f1) return(1);

    cart.mapped=0;
    cart.data=malloc(cart.size);
    fread(cart.data,1,cart.size,f1);

    fclose(f1);
    return(0);
}

int cart_map(char *fname)
{
    // Android build keeps ROM loading simple and portable by reading
    // the whole file instead of relying on Win32 file mappings.
    return(cart_load(fname));
}

void cart_free(void)
{
    if(!cart.size || !cart.data) return;

    free(cart.data);

    memset(&cart,0,sizeof(cart));
}

void cart_dummy(void)
{
    cart_free();
    cart.size=2048*1024;
    cart.data=malloc(cart.size);
    memset(cart.data,'?',1024*1024);
    memset(cart.data,0,64);
    memset(cart.data+4096,0x70,4096);
    cart.data[0x23]='E';
    cart.data[0x22]='R';
    cart.data[0x21]='R';
    cart.data[0x20]='O';
    cart.data[0x27]='R';
    cart.data[0x26]='!';
    cart.osrangestart=1;
    cart.osrangeend=1;
}

void cart_flip(int flip)
{
    int f[4],x;
    byte *s,*send;
    int  *d;

    if(flip==0x0123) return;
    f[0]=(flip>>12)&3;
    f[1]=(flip>> 8)&3;
    f[2]=(flip>> 4)&3;
    f[3]=(flip>> 0)&3;

    s=cart.data;
    send=cart.data+cart.size;
    d=(int *)cart.data;

    while(s<send)
    {
        x =s[f[0]]<<0;
        x|=s[f[1]]<<8;
        x|=s[f[2]]<<16;
        x|=s[f[3]]<<24;
        s+=4;
        *d++=x;
    }
}

void cart_flipheader(char *header)
{
    int f[4],x;
    byte *s,*send;
    int  *d,flip,a;

    a=*(dword *)header;
    flip=0x0123;
    if(a==0x80371240) flip=0x0123;
    if(a==0x37804012) flip=0x1032;
    if(a==0x12408037) flip=0x2301;
    if(a==0x40123780) flip=0x3210;

    f[0]=(flip>>12)&3;
    f[1]=(flip>> 8)&3;
    f[2]=(flip>> 4)&3;
    f[3]=(flip>> 0)&3;

    s=header;
    send=header+64;
    d=(int *)header;

    while(s<send)
    {
        x =s[f[0]]<<0;
        x|=s[f[1]]<<8;
        x|=s[f[2]]<<16;
        x|=s[f[3]]<<24;
        s+=4;
        *d++=x;
    }
}

void cart_open(char *fname,int memmap)
{
    int flip,i;
    char *p;

    cart_free();

    if(*fname=='*')
    {
        cart.isdocalls=1;
        fname++;
    }

    if(*fname=='!')
    {
        memmap=1;
        fname++;
    }

    memset(&cart,0,sizeof(cart));

    strcpy(cart.cartname,fname);
    strcpy(cart.symname,fname);
    p=strchr(cart.symname,'.');
    if(p) strcpy(p,".sym");
    else strcat(cart.symname,".sym");

    flip=cart_check(fname);
    if(flip<0)
    {
        if(flip==-1) exception("File '%s' could not be opened.",fname);
        else         exception("File '%s' format unrecognized.",fname);

        cart_dummy();
        return;
    }

    if(flip==0x0123 && memmap)
    {
        if(cart_map(fname))
        {
            exception("Error %i mapping file.",GetLastError());
            cart_dummy();
            return;
        }
    }
    else
    {
        if(cart_load(fname))
        {
            exception("Error %i loading file.",GetLastError());
            cart_dummy();
            return;
        }
        cart_flip(flip);
    }

    // get title (reverseswap)
    for(i=0;i<20;i++) cart.title[i]=cart.data[0x20+(i^3)];

    if(cart.mapped) print("ROM image '%s' mapped (%iMB): "YELLOW"%s\n",fname,cart.size/1024/1024,cart.title);
    else print("ROM image '%s' loaded (%iMB): "YELLOW"%s\n",fname,cart.size/1024/1024,cart.title);
}
